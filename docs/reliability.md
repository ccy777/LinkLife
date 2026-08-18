# LinkLife Reliability Verification

四组真实故障演练基于最终 8 容器拓扑执行，验证结果如下。演练脚本与复现方式见
`docs/runbook.md` 与 `performance-test/stage6b/run_fault_drills.py`。

## Drill A — Gateway hotspot rate limiting

| item | detail |
|---|---|
| Failure injected | 对 `/api/blog/hot`、`/api/shop/of/type`、`/api/voucher-order/seckill/{id}` 发送热点短 burst |
| Expected invariant | 热点接口精确 429；非热点接口不受影响；429 不产生订单副作用 |
| Observed | 每个热点 API 26/30 次 429，0 个 5xx；非热点请求无 429/5xx；26 个 429 未生成任何订单/submission |
| Recovery | 无需恢复（限流为主动治理） |

## Drill B — Identity outage / circuit breaker

| item | detail |
|---|---|
| Failure injected | 停止 Identity 服务，触发 Social→Identity 熔断（exception-ratio 0.5 / min 5 / window 5s） |
| Expected invariant | 展示型 RPC 降级（不伪造用户）；正确性型 RPC fail-closed；恢复后自动复原 |
| Observed | blog/hot 保持 200 且 name/icon 为空（不伪造用户）；required RPC 返回固定“服务暂不可用”文案；Identity 重启并通过熔断窗口后 name/icon 与 required RPC 恢复 |
| Recovery | Identity 重启 + Nacos 健康 + 熔断窗口过后自动恢复（本地记录 recovery 约 15.73 s） |

## Drill C — Redis restart / AOF persistence

| item | detail |
|---|---|
| Failure injected | `docker compose kill redis` → `rm` → 重建容器（保留 named volume，未 `down -v`） |
| Expected invariant | AOF + volume 恢复 session / stream / group / PEL 与代表 key 及 TTL；ACL namespace 隔离保持 |
| Observed | 同一 Stream message ID 在重启前/后均在 PEL（exact pending，delivery_count=1）；stream XLEN 与 group 保留；跨 namespace 读取返回 NOPERM；功能探测（/user/me、/shop/1、submission、/blog/like）恢复 |
| Recovery | redis 就绪后服务自动重连（本地记录约 20.67 s） |

## Drill D — MySQL outage during seckill

| item | detail |
|---|---|
| Failure injected | 仅停止 MySQL；执行一次已准入秒杀下单 |
| Expected invariant | Redis 已准入但 MySQL 不可用时：消息进入 PEL（不误 ACK、不补偿、不写 DLQ）；恢复后同一 orderId 落库，PEL 条目消失 |
| Observed | pending_baseline=0；exact Stream message id 进入 PEL（consumer=c1，delivery_count=1）；期间 submission 保持 ACCEPTED、DLQ 不增；恢复后同一 orderId 落库（orders=1、distinct=1、duplicate=0），submission=PERSISTED，同一 exact PEL 条目消失 |
| Recovery | 本地演练记录收敛约 8.45 s（≤60s gate） |

## Drill E — RocketMQ Broker publish outage

| item | detail |
|---|---|
| Failure injected | Broker 不可达时发布 timeout 消息 |
| Expected invariant | Outbox 保持 PENDING、retry_count 增加、不写假 SUCCESS；Broker 恢复后自动发送并关单 |
| Observed | 发布失败不假成功；恢复后 publish → 定时消费 → 关单 → Redis 补偿收敛：PASS |
| Recovery | Broker 恢复后由既有 Outbox 重试驱动 |

## Drill F — RocketMQ Consumer outage at dueAt

| item | detail |
|---|---|
| Failure injected | Consumer 在 dueAt 时点停机 |
| Expected invariant | 订单保持 UNPAID；恢复后的消费者处理持久化定时消息并产生一次关闭 |
| Observed | 停机期间订单未关闭；replacement consumer 消费后收敛：PASS |
| Recovery | 消费者重建后自动恢复 |

## Drill G — NameServer + Broker restart

| item | detail |
|---|---|
| Failure injected | 已入 Broker 的定时消息在 NameServer + Broker 重启 |
| Expected invariant | 定时消息从持久化 store 恢复并消费 |
| Observed | 重启后消息被消费并完成关闭：PASS（single-node boundary only） |
| Recovery | 组件重启后自动恢复 |

## Drill H — Broker unavailable at Transaction cold start

| item | detail |
|---|---|
| Failure injected | Broker 在应用启动前已 down，再启动 Transaction |
| Expected invariant | Spring Context 可用；Scheduler 可基于 payment_due_at 关单并回补 MySQL 库存；Broker 恢复后同一进程 Producer/Consumer 自动就绪 |
| Observed | Context/Scheduler 正常；Broker 恢复后 Producer ready ≈ 16.34 s、Consumer ready ≈ 16.42 s（本地观测） |
| Recovery | 后台初始化器自动重试 |

## RocketMQ duplicate / race verification

- 3 条物理重复 timeout 消息：只有 1 次 `UNPAID → CANCELED` CAS 成功，1 次库存返还、1 条状态日志、1 条 ORDER_CLOSED Outbox；
- MQ 与 Scheduler 对同一订单同时触发：只有 1 个 CAS 胜者；
- 本地单节点观测 `consumeAt - dueAt ≈ 199.378 ms`。

以上均为本地单节点开发观测，不代表生产 RocketMQ 集群 HA、SLA 或吞吐保证。

## Summary

| Scenario | Expected behavior | Evidence |
|---|---|---|
| Gateway hotspot limit | 精确 429，无订单副作用 | PASS |
| Identity outage | 展示降级、required fail-closed、恢复验证 | PASS |
| Redis restart | AOF 恢复 session/stream/PEL | PASS |
| MySQL outage during seckill | exact PEL → 恢复后同一 orderId 落库 | PASS |
| Broker publish outage | Outbox 重试收敛、不假成功 | PASS |
| Consumer outage at dueAt | 订单保持 UNPAID，恢复后收敛 | PASS |
| NameServer + Broker restart | 定时消息从持久化 store 恢复 | PASS |
| Broker-down cold start | Context/Scheduler 可用，同进程客户端自动恢复 | PASS |

所有“恢复时间/收敛时间”均为本地演练观测值，不代表 SLA。
