# LinkLife 性能测试

## 测试环境

最终测试采用双机分离方式，避免 JMeter 与服务端争用同一台机器的 CPU 和连接资源。

| 机器 | 硬件 | 任务 |
|---|---|---|
| 服务端 | AMD Ryzen 9 7945HX | 运行 LinkLife、MySQL、Redis、Nacos 等 Docker 服务 |
| 压测端 | Intel Core i5-12400 | 运行 JMeter 5.6.3 / Java 17 |

两台机器通过 1 Gbps 有线网络连接。

## 测试方法

### 热点查询

- 接口：`GET /api/shop/1`；
- 并发线程：500；
- 对照组：Caffeine 关闭与开启；
- 每个缓存配置独立执行 3 轮；
- 每轮独立预热 20 秒，正式采样 60 秒，轮间等待缓存 TTL 到期；
- 汇总值取 3 轮平均值。

### 秒杀

- 接口：`POST /api/voucher-order/seckill/{id}`；
- 场景：1000 个不同用户竞争 300 库存；
- 连续执行 3 轮，每轮使用独立优惠券和用户集合；
- 结束后核对 Redis 准入结果、MySQL 最终订单、不同用户数和数据库剩余库存。

## 热点查询结果

| Caffeine | Run | Samples | QPS | P95 | Errors | Redis GET / request |
|---|---:|---:|---:|---:|---:|---:|
| 关闭 | 1 | 917480 | 15287.511 | 85 ms | 0 | 1.00000000 |
| 关闭 | 2 | 905785 | 15095.159 | 87 ms | 0 | 1.00000000 |
| 关闭 | 3 | 913266 | 15162.724 | 86 ms | 0 | 1.00000000 |
| 开启 | 1 | 965982 | 16095.944 | 82 ms | 0 | 0.00038407 |
| 开启 | 2 | 968671 | 16144.248 | 81 ms | 0 | 0.00014866 |
| 开启 | 3 | 953048 | 15882.545 | 84 ms | 0 | 0.00021615 |

三轮平均结果：

- Caffeine OFF：QPS 15181.80，P95 86.00 ms；
- Caffeine ON：QPS 16040.91，P95 82.33 ms；
- Redis GET / request 由约 1 降至约 0.00025，降幅 99.9750%，简历与 README 按 99.98% 表述。

## 秒杀结果

| Run | Users | Initial stock | Accepted | Out of stock | Persisted | Final stock | P95 | Pass |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 1000 | 300 | 300 | 700 | 300 | 0 | 75 ms | true |
| 2 | 1000 | 300 | 300 | 700 | 300 | 0 | 49 ms | true |
| 3 | 1000 | 300 | 300 | 700 | 300 | 0 | 42 ms | true |

3 轮均满足：

- 300 个不同用户完成准入并最终落库；
- 700 个请求明确返回库存不足；
- 数据库最终库存为 0；
- 0 超卖、0 重复订单。

## 逐轮数据与复算

- [双机正式测试逐轮 CSV](evidence/two-machine-results.csv)
- [JTL 分析脚本](../performance-test/stage6a/analyze_jtl.py)
- [双机结果汇总脚本](../performance-test/two-machine/summarize_results.py)
- [JMeter 场景](../performance-test/stage6a/jmeter)

逐轮 CSV 由原始 JTL、服务端 Redis 监控采样和秒杀一致性核对结果生成。QPS、P50、P95、P99 与错误率均由仓库脚本复算。
