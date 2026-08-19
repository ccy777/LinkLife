# LinkLife Performance Benchmark (public summary)

本文档以**双机正式压测**为最终口径：压测端与服务端分离，结果用于当前 README 与简历的 Benchmark headline。项目早期另有一套同机单机 Baseline，保留在文末 [Historical single-host baseline](#historical-single-host-baseline)，仅作为历史工程验证背景，不再作为当前最终性能口径。

## Test environment

最终正式压测使用双机拓扑：

| 机器 | 硬件 | 角色 |
|---|---|---|
| 服务端 A | AMD Ryzen 9 7945HX | 运行 LinkLife 服务端及 Docker 依赖 |
| 压测端 B | AMD Ryzen 5 5600G | 独立运行 JMeter 5.6.3 / Java 17 |

网络：两台电脑 1 Gbps 有线直连；正式测试期间链路无丢包。

以上均为本地双机工程观测值，**不代表生产 SLA 或线上容量**。

## Methodology

- 热点查询：50 / 100 / 200 / 500 并发档位，每个 profile（Caffeine OFF / ON）、每个档位 3 次正式 run。
- 秒杀：300 / 500 / 800 / 1000 unique-user burst，每档 3 次正式 run；全部 12 次正式运行均完成服务端一致性核对（Redis ordered users、MySQL orders、distinct users、duplicate orders、oversell、Redis stock、PEL、DLQ）。
- 每个 profile、每个档位的汇总值采用 3 次正式 run 的结果。
- Redis GET / request 用于对比 Caffeine 开启前后对 Redis 访问量的影响。

## Hot-query benchmark

Shop 热点查询双机正式结果（3 次正式 run / profile / 档位）：

| concurrency | Caffeine | median QPS | P95 | P99 | Redis GET / request |
|---|---:|---:|---:|---:|---:|
| 50 | OFF | ≈ 12.1K | 6 ms | 8 ms | ≈ 0.935 |
| 50 | ON | ≈ 14.3K | 5 ms | 7 ms | ≈ 0.00028 |
| 100 | OFF | ≈ 14.4K | 11 ms | 15 ms | ≈ 0.920 |
| 100 | ON | ≈ 17.0K | 9 ms | 13 ms | ≈ 0.00019 |
| 200 | OFF | ≈ 15.6K | 21 ms | 30 ms | ≈ 0.913 |
| 200 | ON | ≈ 17.2K | 17 ms | 24 ms | ≈ 0.00017 |
| 500 | OFF | ≈ 15.6K | 40 ms | 52 ms | ≈ 0.879 |
| 500 | ON | ≈ 17.4K | 36 ms | 45 ms | ≈ 0.00013 |

正式 headline：

**双机 500 并发热点查询：QPS ≈ 17.4K，P95 36 ms；Caffeine ON 时 Redis GET/request ≈ 0.00013，相较 OFF（≈ 0.879）降低约 99.98%，即 (0.879 - 0.00013) / 0.879 ≈ 99.98%。**

## Seckill correctness

秒杀档位：300 / 500 / 800 / 1000 unique-user burst，每档 3 次正式 run。每个档位最终均满足：

- Redis ordered users = 对应用户数
- MySQL orders = 对应用户数
- distinct users = 对应用户数
- duplicate orders = 0
- oversell = 0
- Redis stock = 0
- PEL = 0
- DLQ = 0

最高正式验证档位：**1000 unique-user burst，连续 3 轮**：

| Metric | Result |
|---|---:|
| Persisted orders | 1000 |
| Distinct users | 1000 |
| Duplicate orders | 0 |
| Oversell | 0 |
| HTTP errors | 0 |
| P95 | ≈ 40 ms |
| P99 | ≈ 44 ms |
| PEL | 0 |
| DLQ | 0 |

正式 headline：

**双机压测下 1000 用户突发秒杀连续 3 轮 0 超卖、0 重复订单，P95 ≈ 40 ms。**

## Usage boundary

- 以上结果来自本地双机工程压测，是工程观测值，**不代表生产 SLA、最大容量或线上流量**。
- “1000 unique-user burst”仅描述该验证档位，不表述为“最大支持 1000 并发”“1000 TPS”“系统容量为 1000”或“1000 请求同一毫秒”。
- QPS、P95/P99 与 Redis GET 对比仅用于描述该双机环境的实测表现，不外推生产容量结论。

## Historical single-host baseline

项目早期曾使用同机 Docker + JMeter 做 Benchmark：JMeter 与 Docker 服务端运行在同一台机器（Windows 11 Home，AMD Ryzen 9 7945HX，16 GB 内存，Docker Desktop 29.6.1），两者竞争 CPU，因此多数 run 为 client-limited 观测。原始逐 run 数据见 `docs/evidence/performance-results.csv`（42 个官方 run）。

代表性历史数据：

- 50 并发 Shop Caffeine ON：Median QPS 3089.865，P95 25 ms，Redis GET/request ≈ 0.001；
- 300-user 秒杀：3/3 correctness，3 次正式 run 的 P95 为 307 / 252 / 109 ms；
- 500-user 档存在客户端侧连接失败（JMeter/Windows ephemeral port 耗尽），不得表述为“500 用户全部成功”。

这些旧单机结果保留为历史工程验证背景，**不再作为当前 README 和简历的最终性能口径**；最终口径以本文档上文的双机正式压测为准。
