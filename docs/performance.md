# LinkLife Performance Benchmark (public summary)

本文件是仓库冻结基准记录的公开精简版。原始逐 run 数据见
`docs/evidence/performance-results.csv`（42 个官方 run）。

## Test environment

- Windows 11 Home，AMD Ryzen 9 7945HX（32 逻辑核），16 GB 内存。
- Docker Desktop 29.6.1；Java 17（Temurin）；JMeter 5.6.3；Python 3.12。
- 8 容器本地 Compose 拓扑（mysql/redis/nacos + 4 services + gateway），业务入口仅 Gateway `127.0.0.1:8080/api/**`。
- 未设置 per-container 资源硬限制；JMeter 与容器同机竞争 CPU。

## Methodology

- Shop / ShopType：每场景 3 runs；30s warm-up + 60s measured（warm-up 不计入）；汇总用 3 runs median，并给出 min/max/CV。
- Blog Hot / Seckill：使用冻结的原始 JTL 重新分析（未重发流量）。
- throughput duration = `max(timeStamp + elapsed) - min(timeStamp)`。
- Redis 统计仅计 measured window 的 INFO commandstats delta，并记录同长无流量背景窗。
- 42 个官方 run：Shop（18）+ ShopType（6）+ Blog Hot（9）+ Seckill（9）。

## Headline observations

| observation | value |
|---|---|
| Shop measured-window Redis GET/request | OFF ≈ 1.0；ON ≈ 0.001（Caffeine L1 命中后） |
| ShopType measured-window Redis GET/request | OFF ≈ 1.0；ON ≈ 0.0001 |
| 100-user seckill burst | accepted=100/run，orders=100，distinct=100，duplicate=0，3/3 correctness |
| 300-user seckill burst | accepted=300/run，orders=300，distinct=300，duplicate=0，median P95=252 ms |
| Blog Hot（25/50/100 threads） | 无 429、无 5xx（基准 profile 关闭 Gateway 限流） |

### Shop detail median QPS / P95（local Docker, client-limited）

| threads | profile | median QPS | median P95 ms |
|---|---:|---:|---:|
| 50 | off | 2152.543 | 29 |
| 50 | on | 3089.865 | 25 |
| 100 | off | 2588.953 | 49 |
| 100 | on | 6956.710 | 30 |
| 200 | off | 5653.058 | 58 |
| 200 | on | 2928.941 | 69 |

### ShopType list（100 threads）

| profile | median QPS | median P95 ms |
|---|---:|---:|
| off | 2670.365 | 46 |
| on | 3015.987 | 38 |

### Blog Hot（median）

| threads | median QPS | median P95 ms |
|---|---:|---:|
| 25 | 910.235 | 37 |
| 50 | 1797.147 | 48 |
| 100 | 1271.735 | 114 |

### Seckill burst（correctness）

| users | accepted | orders | distinct | duplicates | correctness |
|---|---:|---:|---:|---:|---|
| 100 | 100/100/100 | 100 | 100 | 0 | pass |
| 300 | 300/300/300 | 300 | 300 | 0 | pass |
| 500 | 432/493/483 | 432/493/483 | same | 0 | pass (client failures) |

## Client-limited caveat

绝大多数 run 中 host CPU 接近饱和（>95%），QPS/P95 属 client-limited 观测：JMeter 与容器同机竞争 CPU，且 QPS 波动大（CV 高）。这些数字只用于描述本机可复现基准，不用于容量/SLA 结论。

## 500-user client failures

500-user seckill run 存在客户端侧连接失败（JMeter/Windows ephemeral port exhaustion，请求未到达 Gateway）：r1=68、r2=7、r3=17。因此不得表述为“500 用户全部成功”；正确性判断仅基于实际到达并被接受的请求。

## Usable vs not usable

可用于结论：

- measured-window 下 Caffeine 将 Shop 的 Redis GET/request 从约 1 降至约 0.001。
- 100/300-user burst 中未观察到超卖或重复订单（3/3 runs correctness）。
- 42 个 run 的吞吐/延迟为可复现的本机观测。

不可用于结论：

- 任何绝对 QPS/SLA/容量表述（client-limited）。
- “性能提升 X%”（QPS 波动大，部分 profile 反直觉）。
- 500-user“全部成功”表述。
- 生产规模或在线流量的外推。
