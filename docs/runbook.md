# LinkLife 运行与演示指南

## 环境要求

- Windows + Docker Desktop
- JDK 17
- Maven
- Python 3.12

## 快速启动

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

如需体验完整的未支付订单自动关闭链路，使用仓库提供的 Demo 编排：

```bat
docker compose --env-file .env -f backend\deploy\docker-compose.stage4.yml -f backend\deploy\docker-compose.demo.yml up -d --build
```

该编排会启动 RocketMQ，并配置好超时消息所需的 Topic 与 Consumer Group。

## 基础接口演示

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

## 秒杀演示

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

## RocketMQ 超时关单

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

配置说明：

- `LINKLIFE_ORDER_PAYMENT_TIMEOUT` 使用整秒 Duration（如 `90s`、`15m`、`24h`）；
- 启用 MQ 时自动要求 Outbox 与 Scheduler 开关同时打开；
- Scheduler 与 MQ 共用 `payment_due_at` 和统一关闭逻辑。

本地单节点集成验证拓扑（NameServer + Broker + gRPC Proxy + MySQL + Redis + 可选集成执行器）：

```bat
docker compose -f backend\deploy\rocketmq-timeout-it\docker-compose.yml up -d
```

该拓扑的诊断端口绑定 `127.0.0.1`，用于本地开发和集成验证。

## 超时关闭演示

创建秒杀券并下单后，订单会在 `payment_due_at` 到期时被 RocketMQ（启用时）或 Scheduler（兜底）关闭：

```text
GET /api/voucher-order/<ORDER_ID>   → status=4 (CANCELED)
MySQL tb_voucher_order.payment_due_at 与订单创建时冻结值一致
```

启用 MQ 时，可观察 Transaction 日志中的 timeout 消费记录；关闭 MQ 时 Scheduler 按 `payment_due_at` 扫描关闭。

## 缓存演示

```bat
curl http://127.0.0.1:8080/api/shop/1
```

重复查询可观察 Redis `merchant:cache:shop:1` 命中；启用 `LINKLIFE_LOCAL_CACHE_ENABLED=true` 后，可继续观察 Caffeine 对 Redis 访问量的削减效果。

## Sentinel 限流演示

```bat
for /L %i in (1,1,8) do curl -s -o NUL -w "%{http_code}\n" "http://127.0.0.1:8080/api/blog/hot?current=1"
```

可见 200 与 429（`success=false`、`errorMsg=请求过于频繁，请稍后再试`）混合返回。

## 故障演练

```bat
py performance-test\stage6b\run_fault_drills.py --help
```

脚本支持 Gateway 限流、Identity 熔断恢复、Redis AOF 重启和 MySQL 中断后的秒杀 Pending 恢复。RocketMQ 发布中断、Consumer 停机、Broker/NameServer 重启与冷启动恢复通过本地集成环境执行，完整结果见 `docs/reliability.md`。

## 停止与清理

```bat
docker compose --env-file .env -f backend\deploy\docker-compose.stage4.yml down -v
del .env
```
