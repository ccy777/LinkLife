# LinkLife 可靠性验证

项目围绕限流、服务中断、缓存恢复、数据库故障和消息链路执行真实演练。Stage6B 脚本负责 Gateway、Identity、Redis 与 MySQL 场景；RocketMQ 场景通过本地集成环境执行。

## 验证结果

| 场景 | 验证内容 | 结果 |
|---|---|---|
| Gateway 热点过载 | 热点接口返回 429，非热点请求正常，限流请求不产生订单 | 通过 |
| Identity 服务中断 | 展示接口降级、关键接口阻断，服务恢复后调用自动恢复 | 通过 |
| Redis 重启 | AOF 恢复会话、Stream、Consumer Group 与 PEL | 通过 |
| 秒杀期间 MySQL 中断 | 已准入消息保留在 PEL，数据库恢复后继续落库 | 通过 |
| RocketMQ 发布中断 | Outbox 保留待发送任务，Broker 恢复后完成投递与关单 | 通过 |
| RocketMQ Consumer 停机 | 订单保持未支付，Consumer 恢复后处理定时消息 | 通过 |
| NameServer 与 Broker 重启 | 已持久化定时消息恢复后继续消费 | 通过 |
| Broker 冷启动不可用 | Transaction 与 Scheduler 正常启动，Broker 恢复后客户端自动连接 | 通过 |

## Gateway 热点限流

对动态热点、分类商铺和秒杀接口发送短时突发请求：

- 每个热点接口 30 次请求中出现 26 次 429；
- 0 个 5xx；
- 非热点接口保持正常；
- 被限流的秒杀请求没有生成订单或提交记录。

## Identity 中断与恢复

停止 Identity 后触发 Social → Identity 熔断：

- 动态列表继续返回基础内容，用户名称和头像留空；
- 依赖真实用户信息的接口返回“服务暂不可用”；
- Identity 恢复并经过熔断窗口后，用户信息和关键调用自动恢复；
- 本地恢复观测约 15.73 秒。

## Redis 重启恢复

Redis 容器重建时保留 AOF 数据卷，恢复后核对：

- 登录会话与代表性业务键；
- Stream 长度与 Consumer Group；
- 重启前后的同一条 PEL 消息；
- Redis ACL 命名空间隔离；
- 用户、商铺、提交状态和点赞接口。

服务自动重新连接 Redis，本地恢复观测约 20.67 秒。

## 秒杀期间 MySQL 中断

MySQL 停止后发起一次秒杀请求，Redis 完成准入，但消费者无法持久化订单：

```text
Redis 准入成功
  ↓
消息进入 PEL，不提前 ACK
  ↓
MySQL 恢复
  ↓
同一 orderId 完成落库
  ↓
提交状态变为 PERSISTED，PEL 清空
```

整个过程中没有重复订单、错误补偿或 DLQ 增量，本地收敛观测约 8.45 秒。

## RocketMQ 消息链路

### 发布中断

Broker 不可达时，Outbox 保持待发送状态并增加重试次数。Broker 恢复后，消息完成发布、定时消费、订单关闭和 Redis 库存补偿。

### Consumer 停机

Consumer 在订单到期时停机，订单保持 `UNPAID`；Consumer 恢复后继续消费 Broker 中的定时消息并完成关单。

### Broker 与 NameServer 重启

在定时消息写入 Broker 后重启 NameServer 与 Broker，消息从持久化存储恢复，并在到期后完成消费。

### 冷启动恢复

Broker 未启动时先启动 Transaction：Spring Context 与 Scheduler 正常工作；Broker 恢复后，同一进程中的 Producer 和 Consumer 自动连接。

## 重复消息与并发竞争

- 连续投递 3 条相同超时消息，只有一次 `UNPAID → CANCELED` 条件更新成功；
- 库存仅返还一次，状态日志和 `ORDER_CLOSED` Outbox 各产生一条；
- RocketMQ 与 Scheduler 同时关闭同一订单时，同样只有一个成功者；
- 定时消息本地到期偏差观测约 199 ms。

## 复现入口

- [运行与演示指南](runbook.md)
- [Stage6B 故障演练脚本](../performance-test/stage6b/run_fault_drills.py)
- [RocketMQ 本地集成环境](../backend/deploy/rocketmq-timeout-it)
