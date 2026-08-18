# RocketMQ timeout task integration topology

This Compose file is an isolated, single-node verification topology for the order-payment timeout task. It uses only resources prefixed with `linklife-codex-it-rmq-timeout` and does not model cluster HA.

It starts MySQL 8.0.42, Redis 7.2.10, RocketMQ 5.5.0 NameServer, and a Broker with the 5.x gRPC Proxy. The one-shot `rocketmq-init` service idempotently creates a `DELAY` topic and the dedicated consumer group. MySQL imports the transaction module's production schema only when the task-owned data volume is empty.

The `integration-runner` profile executes the opt-in manual integration tests inside the same Docker network, because Docker Desktop does not route the Broker's container address back through the Windows host. Its JVM timezone is pinned to `Asia/Shanghai` to match the business and MySQL `DATETIME` semantics. All passwords are empty and valid only inside this isolated test topology.

Any diagnostic host ports are bound to `127.0.0.1` only. The integration runner uses service names on the private Compose network; the empty-password MySQL/Redis fixtures are never exposed on non-loopback interfaces.

Example lifecycle:

```text
docker compose -p linklife-codex-it-rmq-timeout up -d
docker compose -p linklife-codex-it-rmq-timeout --profile manual-test run --rm integration-runner <maven command>
docker compose -p linklife-codex-it-rmq-timeout down -v
```

Never use this topology or its empty credentials in production. Cleanup must target this exact Compose project; do not use a global Docker prune.
