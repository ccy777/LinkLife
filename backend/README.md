# LinkLife Backend Guide

## Modules

```text
linklife-common-core       cross-service contracts, Result, user context, session key contract
linklife-common-web        user-context filter, admin mutation guard, global exception handler
linklife-gateway           reactive gateway: session auth, route forwarding, Sentinel hotspot rules
linklife-identity-service  users, login verification codes, sessions, internal user-summary API
linklife-merchant-service  shops, shop types, uploads, Caffeine L1 + Redis L2 cache, Redis GEO index
linklife-transaction-service  vouchers, seckill admission, async order persistence, outbox,
                              RocketMQ payment-timeout trigger, unified close kernel
linklife-social-service    blogs, follows, likes, Social→Identity batch user summary (OpenFeign)
```

## Order chain

Seckill requests are admitted atomically in Redis, then persisted
asynchronously in a MySQL local transaction:

```text
POST /voucher-order/seckill/{id}
  → Redis Lua admission (stock, one-user-one-order, XADD stream)
  → submission state ACCEPTED
  → OrderStreamConsumer (group + PEL)
  → MySQL local transaction: stock -1 + INSERT UNPAID order
    (payment_due_at frozen at order creation)
  → submission state PERSISTED → ACK
```

Successful admission does not mean the order has been persisted yet; the
client polls the submission state (`ACCEPTED → PROCESSING → PERSISTED`).

## Unpaid-order timeout close

`payment_due_at` is the per-order absolute expiration fact frozen inside the
order-creation transaction. The RocketMQ timer trigger and the Scheduler
repair/sweep fallback share this single fact and the same close kernel:

```text
                 payment_due_at
                       |
          +------------+------------+
          |                         |
          v                         v
Local Outbox -> RocketMQ       Scheduler
          |                         |
          +------------+------------+
                       |
                       v
          OrderCloseTransactionService
                       |
                       v
             UNPAID -> CANCELED CAS
                       |
             +---------+---------+
             |                   |
         MySQL stock          status log
                                 |
                         ORDER_CLOSED Outbox
                                 |
                         Redis idempotent
                           compensation
```

- `payment_due_at` is set in the same MySQL transaction that inserts the
  order; a later runtime change of `payment-timeout` cannot reinterpret
  historical orders.
- RocketMQ is the active timeout trigger; the Scheduler is a repair/sweep
  fallback over the same fact.
- Duplicate messages are absorbed by the `UNPAID → CANCELED` MySQL CAS.
- `ORDER_CLOSED` is published through the local Outbox to drive idempotent
  Redis stock compensation.
- RocketMQ is a Transaction-internal timeout channel, not a cross-service
  event bus.

## Startup

```bat
mvn -f backend/pom.xml -DskipTests package
copy backend\deploy\stage4.env.example .env   :: fill REQUIRED placeholders; .env is gitignored
docker compose --env-file .env -f backend\deploy\docker-compose.stage4.yml up -d
```

## Service ports

| component | port |
|---|---:|
| gateway | 8080 (only external entry) |
| identity-service | 8081 (container-internal) |
| merchant-service | 8082 (container-internal) |
| transaction-service | 8083 (container-internal) |
| social-service | 8084 (container-internal) |
| mysql | 127.0.0.1:13306 |
| redis | 127.0.0.1:16379 |
| nacos | 127.0.0.1:18848 / 19848 |

## Database ownership

Four independent databases, one per business service, each with a
least-privilege account:

```text
linklife_identity     identity-service
linklife_merchant     merchant-service
linklife_transaction  transaction-service
linklife_social       social-service
```

Authoritative schema: each service's `src/main/resources/db/schema.sql`;
upgrade scripts live in `db/upgrade/`.

## Migrations

Legacy monolith migration scripts are portable (POSIX `sh`) and stay in the
source archive; invoke them explicitly when migrating an existing database:

```sh
sh backend/deploy/migration/migrate-stage3-mysql.sh
sh backend/deploy/migration/migrate-stage3-redis.sh
```

Existing databases that already have orders must run
`002_add_voucher_order_payment_due_at.sql` to backfill `payment_due_at`
before enabling the timeout feature. The default backfill is
`create_time + 15 MINUTE`; an environment that historically used a custom
timeout must backfill with that historical value instead.

## Redis namespace

```text
identity:*        identity-service (login code / session / sign)
merchant:*        merchant-service (cache / lock / shop GEO)
transaction:*     transaction-service (seckill / submission / stream / DLQ)
social:*          social-service (lock namespace)
identity:login:token:* / login:token:*   gateway session read/refresh
```

Redis runs with per-service ACL users; a service cannot access another
service's namespace (cross-namespace access returns NOPERM).

## Key configuration

- `LINKLIFE_PRODUCTION_VALIDATION_ENABLED` — fail-fast runtime validation.
- `LINKLIFE_CONSOLE_VERIFICATION_CODE_ENABLED` — dev-only console code output.
- `LINKLIFE_LOCAL_CACHE_*` — merchant Caffeine L1 settings (disable for A/B).
- `LINKLIFE_SENTINEL_*` — gateway hotspot QPS and Social→Identity breaker.
- `LINKLIFE_ORDER_TIMEOUT_ENABLED` / `LINKLIFE_OUTBOX_ENABLED` — transaction
  reliability features (default off).
- `LINKLIFE_ORDER_TIMEOUT_MQ_ENABLED` — RocketMQ timeout trigger (default
  off; when enabled it requires endpoints/topic/tag/consumer-group and the
  Outbox/Scheduler guards).
- `LINKLIFE_ORDER_PAYMENT_TIMEOUT` — whole-second Duration in
  `[1s, 24h]` (default `15m`); sub-second values fail startup.
- `LINKLIFE_ADMIN_USER_IDS` — admin write guard allowlist.

## RocketMQ (optional, default off)

The RocketMQ payment-timeout feature is safely disabled by default. Enabling
it requires a RocketMQ 5.x endpoint with timer-message support, plus:

```text
linklife.trade.order-timeout.rocketmq.enabled=true
linklife.trade.order-timeout.rocketmq.endpoints=<proxy endpoint>
linklife.trade.order-timeout.rocketmq.topic=linklife-order-payment-timeout
linklife.trade.order-timeout.rocketmq.tag=PAYMENT_TIMEOUT_CHECK
linklife.trade.order-timeout.rocketmq.consumer-group=<group>
```

`linklife.trade.order-timeout.payment-timeout` must be a whole-second
Duration between `1s` and `24h`. The task-owned single-node verification
topology lives in `backend/deploy/rocketmq-timeout-it/` (loopback ports,
test-only empty credentials, integration runner behind a manual profile).
It is not a production HA cluster.

## Test commands

```bat
mvn -f backend/pom.xml clean test
mvn -f backend/pom.xml -DskipTests package
```

Final frozen reactor: 1006 tests, 0 failures, 0 errors, 5 skipped
(the 5 skipped are pre-existing environment-dependent upload tests).
