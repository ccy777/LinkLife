# LinkLife 后端开发指南

## 模块说明

```text
linklife-common-core          跨服务公共契约、Result、用户上下文与会话键规范
linklife-common-web           用户上下文过滤、管理员校验与全局异常处理
linklife-gateway              会话认证、路由转发与 Sentinel 热点限流
linklife-identity-service     用户、验证码、登录会话与用户摘要接口
linklife-merchant-service     商铺、分类、文件上传、两级缓存与 GEO 索引
linklife-transaction-service  优惠券、秒杀、异步订单、Outbox 与超时关单
linklife-social-service       动态、关注、点赞及用户摘要批量查询
```

## 秒杀订单链路

```text
POST /voucher-order/seckill/{id}
  → Redis Lua 原子准入（库存、一人一单、写入 Stream）
  → submission = ACCEPTED
  → OrderStreamConsumer 消费（Consumer Group + PEL）
  → MySQL 本地事务：库存 -1 + 创建 UNPAID 订单 + 冻结 payment_due_at
  → submission = PERSISTED
  → ACK
```

前端通过 `ACCEPTED → PROCESSING → PERSISTED` 区分 Redis 准入、消费处理和数据库持久化三个阶段。

## 未支付订单关闭

`payment_due_at` 在订单创建事务内写入，RocketMQ 定时消息与 Scheduler 定时修复共用同一个到期时间和关闭入口：

```text
                 payment_due_at
                       │
          ┌────────────┴────────────┐
          ↓                         ↓
Local Outbox → RocketMQ         Scheduler
          └────────────┬────────────┘
                       ↓
          OrderCloseTransactionService
                       ↓
             UNPAID → CANCELED CAS
                       ↓
       MySQL 库存返还 + ORDER_CLOSED Outbox
                       ↓
               Redis 幂等库存补偿
```

- 订单和超时发布意图在同一个 MySQL 事务内提交；
- Broker 暂时不可用时，Outbox 保留待发送记录并继续重试；
- 重复消息及 MQ/Scheduler 竞争由 MySQL 条件更新吸收；
- `ORDER_CLOSED` 事件通过 Lua 幂等补偿 Redis 库存。

## 启动后端

```bat
mvn -f backend/pom.xml -DskipTests package
copy backend\deploy\stage4.env.example .env
docker compose --env-file .env -f backend\deploy\docker-compose.stage4.yml up -d
```

## 服务端口

| 组件 | 端口 |
|---|---:|
| Gateway | 8080 |
| Identity | 8081（容器内部） |
| Merchant | 8082（容器内部） |
| Transaction | 8083（容器内部） |
| Social | 8084（容器内部） |
| MySQL | 127.0.0.1:13306 |
| Redis | 127.0.0.1:16379 |
| Nacos | 127.0.0.1:18848 / 19848 |

## 数据库归属

```text
linklife_identity     Identity
linklife_merchant     Merchant
linklife_transaction  Transaction
linklife_social       Social
```

每个业务服务使用独立数据库与最小权限账号。建表脚本位于各服务的 `src/main/resources/db/schema.sql`，升级脚本位于 `db/upgrade/`。

历史数据库迁移命令：

```sh
sh backend/deploy/migration/migrate-stage3-mysql.sh
sh backend/deploy/migration/migrate-stage3-redis.sh
```

已有订单数据的环境在启用超时关单前，需要执行 `002_add_voucher_order_payment_due_at.sql` 补充 `payment_due_at`。

## Redis 命名空间

```text
identity:*        用户、验证码、会话与签到
merchant:*        商铺缓存、互斥锁与 GEO
transaction:*     秒杀、提交状态、Stream 与 DLQ
social:*          社交模块锁与业务键
```

Redis 使用按服务划分的 ACL 用户，Gateway 只读取会话相关键。

## 关键配置

| 配置 | 作用 |
|---|---|
| `LINKLIFE_LOCAL_CACHE_*` | Caffeine L1 缓存及 A/B 测试开关 |
| `LINKLIFE_SENTINEL_*` | Gateway 热点限流与服务熔断参数 |
| `LINKLIFE_ORDER_TIMEOUT_ENABLED` | 未支付订单超时处理 |
| `LINKLIFE_OUTBOX_ENABLED` | 本地消息表投递 |
| `LINKLIFE_ORDER_TIMEOUT_MQ_ENABLED` | RocketMQ 定时消息触发 |
| `LINKLIFE_ORDER_PAYMENT_TIMEOUT` | 订单支付期限，默认 `15m` |
| `LINKLIFE_ADMIN_USER_IDS` | 管理写接口用户白名单 |

## RocketMQ 超时触发

启用时配置 RocketMQ 5.x Proxy 地址、Topic、Tag 与消费组：

```text
linklife.trade.order-timeout.rocketmq.enabled=true
linklife.trade.order-timeout.rocketmq.endpoints=<proxy endpoint>
linklife.trade.order-timeout.rocketmq.topic=linklife-order-payment-timeout
linklife.trade.order-timeout.rocketmq.tag=PAYMENT_TIMEOUT_CHECK
linklife.trade.order-timeout.rocketmq.consumer-group=<group>
```

本地集成环境位于 `backend/deploy/rocketmq-timeout-it/`。

## 测试命令

```bat
mvn -f backend/pom.xml clean test
mvn -f backend/pom.xml -DskipTests package
```

当前全量回归结果：1011 项测试，0 Failure，0 Error，5 项跳过。
