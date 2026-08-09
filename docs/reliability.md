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

## Summary

| Scenario | Expected behavior | Evidence |
|---|---|---|
| Gateway hotspot limit | 精确 429，无订单副作用 | PASS |
| Identity outage | 展示降级、required fail-closed、恢复验证 | PASS |
| Redis restart | AOF 恢复 session/stream/PEL | PASS |
| MySQL outage during seckill | exact PEL → 恢复后同一 orderId 落库 | PASS |

所有“恢复时间/收敛时间”均为本地演练观测值，不代表 SLA。
