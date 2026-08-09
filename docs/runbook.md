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

脚本支持四组演练：Gateway 限流、Identity 熔断恢复、Redis AOF 重启、MySQL-down 秒杀 Pending 恢复；完整结果摘要见 `docs/reliability.md`。

## Cleanup

```bat
docker compose --env-file .env -f backend\deploy\docker-compose.stage4.yml down -v
del .env
```
