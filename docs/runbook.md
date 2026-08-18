# LinkLife Demo / Reproduction Runbook

## Prerequisites

- Windows + Docker Desktop
- JDK 17
- Maven
- Python 3.12

## Quick Start

```bat
cd backend
mvn -DskipTests package
cd ..
copy backend\deploy\stage4.env.example .env
```

将 `.env` 中所有 `REQUIRED` 占位符替换为本地开发用密码（`.env` 已被 gitignore，不要提交真实密码），然后：

```bat
docker compose --env-file .env -f backend\deploy\docker-compose.stage4.yml up -d
docker ps --filter "name=linklife-stage4" --format "{{.Names}} {{.Status}}"
curl http://127.0.0.1:8080/actuator/health
```

> RocketMQ 未支付超时功能**默认安全关闭**（`LINKLIFE_ORDER_TIMEOUT_MQ_ENABLED=false`），默认启动命令不需要任何 RocketMQ 配置。

## Core demo flow

1. 登录验证码（开发模式输出到 Identity 容器日志）：

```bat
curl -X POST "http://127.0.0.1:8080/api/user/code?phone=13800138000"
docker logs linklife-stage4-identity | findstr code=
curl -X POST http://127.0.0.1:8080/api/user/login -H "Content-Type: application/json" -d "{\"phone\":\"13800138000\",\"code\":\"<CODE>\"}"
```

2. 基础查询：

```text
GET http://127.0.0.1:8080/api/shop/1
GET http://127.0.0.1:8080/api/shop-type/list
GET http://127.0.0.1:8080/api/blog/hot?current=1
```

## Seckill demo

管理端创建秒杀券（需 admin token）：

```bat
curl -X POST http://127.0.0.1:8080/api/voucher/seckill ^
  -H "Authorization: <ADMIN_TOKEN>" -H "Content-Type: application/json" ^
  -d "{\"shopId\":1,\"title\":\"demo\",\"stock\":1,\"type\":1,\"status\":1,\"payValue\":100,\"actualValue\":1000,\"beginTime\":\"<UTC-now-1min>\",\"endTime\":\"<UTC-now+2h>\"}"
```

用户下单与提交状态查询：

```bat
curl -X POST http://127.0.0.1:8080/api/voucher-order/seckill/<VOUCHER_ID> -H "Authorization: <USER_TOKEN>"
curl http://127.0.0.1:8080/api/voucher-order/submissions/<ORDER_ID> -H "Authorization: <USER_TOKEN>"
```

## RocketMQ timeout（可选）

启用 RocketMQ 未支付超时触发需要：

1. 已有订单表具备 `payment_due_at`（新库直接使用 `schema.sql`；旧库执行
   `backend/linklife-transaction-service/src/main/resources/db/upgrade/002_add_voucher_order_payment_due_at.sql`）；
2. 设置以下环境变量并满足配置守卫：

```text
LINKLIFE_ORDER_TIMEOUT_ENABLED=true
LINKLIFE_OUTBOX_ENABLED=true
LINKLIFE_ORDER_TIMEOUT_MQ_ENABLED=true
LINKLIFE_ORDER_TIMEOUT_MQ_TOPIC=linklife-order-payment-timeout
LINKLIFE_ORDER_TIMEOUT_MQ_TAG=PAYMENT_TIMEOUT_CHECK
LINKLIFE_ORDER_TIMEOUT_MQ_CONSUMER_GROUP=linklife-transaction-order-timeout-v1
LINKLIFE_ORDER_TIMEOUT_MQ_SSL_ENABLED=false        # 本地 gRPC Proxy
LINKLIFE_ROCKETMQ_ENDPOINTS=<proxy endpoint e.g. 127.0.0.1:38081>
```

配置约束：

- `LINKLIFE_ORDER_PAYMENT_TIMEOUT` 必须是 `[1s, 24h]` 的**整秒 Duration**（如 `90s`、`15m`、`24h`）；`1001ms` 等亚秒值启动失败；
- 启用 MQ 时自动要求 Outbox 与 Scheduler 开关同时打开；
- Scheduler 作为 repair/sweep fallback 与 MQ 共用 `payment_due_at` 与统一关闭 CAS。

任务专属单节点验证拓扑（NameServer + Broker + gRPC Proxy + MySQL + Redis + 可选集成执行器）：

```bat
docker compose -f backend\deploy\rocketmq-timeout-it\docker-compose.yml up -d
```

该拓扑端口只绑定 `127.0.0.1`、仅用于本地开发/集成验证，空密码仅存在于隔离测试环境，**不得用于生产**。

## Timeout demo

创建秒杀券并下单后，订单会在 `payment_due_at` 到期时被 RocketMQ（启用时）或 Scheduler（兜底）关闭：

```text
GET /api/voucher-order/<ORDER_ID>   → status=4 (CANCELED)
MySQL tb_voucher_order.payment_due_at 与订单创建时冻结值一致
```

启用 MQ 时，可观察 Transaction 日志中的 timeout 消费记录；关闭 MQ 时 Scheduler 按 `payment_due_at` 扫描关闭。

## Cache demo

```bat
curl http://127.0.0.1:8080/api/shop/1
```

重复查询可观察 Redis `merchant:cache:shop:1` 命中；启用 `LINKLIFE_LOCAL_CACHE_ENABLED=true` 时，本地基准中 measured-window 的 Redis GET/request 从约 1 降至约 0.001。

## Sentinel demo

```bat
for /L %i in (1,1,8) do curl -s -o NUL -w "%{http_code}\n" "http://127.0.0.1:8080/api/blog/hot?current=1"
```

可见 200 与 429（`success=false`、`errorMsg=请求过于频繁，请稍后再试`）混合返回。

## Fault drill overview

```bat
py performance-test\stage6b\run_fault_drills.py --help
```

脚本支持四组演练：Gateway 限流、Identity 熔断恢复、Redis AOF 重启、MySQL-down 秒杀 Pending 恢复；RocketMQ 相关演练（Broker 发布中断、Consumer 停机、Broker/NameServer 重启、Broker-down 冷启动）通过任务专属拓扑与手工集成测试执行；完整结果摘要见 `docs/reliability.md`。

## Cleanup

```bat
docker compose --env-file .env -f backend\deploy\docker-compose.stage4.yml down -v
del .env
```
