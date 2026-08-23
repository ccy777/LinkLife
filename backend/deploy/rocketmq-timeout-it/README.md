# RocketMQ 超时关单集成环境

该 Compose 环境用于验证未支付订单超时关闭链路，包含 MySQL 8、Redis 7、RocketMQ 5.5 NameServer、Broker 与 gRPC Proxy。

`rocketmq-init` 会创建定时消息 Topic 和消费组；`integration-runner` 可在同一 Docker 网络内执行手工集成测试。JVM 时区固定为 `Asia/Shanghai`，与业务时间和 MySQL `DATETIME` 保持一致。

诊断端口统一绑定 `127.0.0.1`，环境中的空密码仅用于本地隔离测试。

```text
docker compose -p linklife-rmq-it-timeout up -d
docker compose -p linklife-rmq-it-timeout --profile manual-test run --rm integration-runner <maven command>
docker compose -p linklife-rmq-it-timeout down -v
```

清理时使用上面的 Compose 项目名，避免影响其他 Docker 环境。
