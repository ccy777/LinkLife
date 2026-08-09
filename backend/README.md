# LinkLife Backend Guide

## Modules

```text
linklife-common-core       cross-service contracts, Result, user context, session key contract
linklife-common-web        user-context filter, admin mutation guard, global exception handler
linklife-gateway           reactive gateway: session auth, route forwarding, Sentinel hotspot rules
linklife-identity-service  users, login verification codes, sessions, internal user-summary API
linklife-merchant-service  shops, shop types, uploads, Caffeine L1 + Redis L2 cache, Redis GEO index
linklife-transaction-service  vouchers, seckill admission, async order persistence, outbox, timeout close
linklife-social-service    blogs, follows, likes, Social→Identity batch user summary (OpenFeign)
```

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
  reliability features.
- `LINKLIFE_ADMIN_USER_IDS` — admin write guard allowlist.

## Test commands

```bat
mvn -f backend/pom.xml clean test
mvn -f backend/pom.xml -DskipTests package
```
