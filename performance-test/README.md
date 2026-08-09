# performance-test

Benchmark and fault-drill tooling for the LinkLife microservice stack.

```text
performance-test/
├── stage6a/            benchmark orchestrator + JMeter scenarios + analyzer
├── stage6b/            fault-drill orchestrator (drill-a..d)
└── deploy/             stage6a / stage6b Compose profiles
```

## What it does

- `stage6a/` runs the official local benchmark scenarios (Shop Detail
  Caffeine OFF/ON, ShopType, Blog Hot, Seckill bursts) against the 8-container
  stack and analyzes the raw JMeter JTLs.
- `stage6b/` runs four real failure drills (Gateway rate limiting, Identity
  outage/breaker, Redis restart with AOF persistence, MySQL-down seckill
  Pending recovery).

## Local output

All locally generated JTL / JSON / log / result files are written under the
gitignored `.linklife-local/` directory and are never committed:

```text
.linklife-local/evidence/stage6a/
.linklife-local/evidence/stage6b/
.linklife-local/results/
.linklife-local/reports/
```

The public repository freezes only:

- benchmark summary: `docs/performance.md`
- official per-run results: `docs/evidence/performance-results.csv`
- fault drill summary: `docs/reliability.md`

Re-running the scripts never overwrites those frozen files.

## Integrity note

JMeter scenario semantics, the analyzer statistics formulas, the benchmark
workload and the fault-drill correctness logic are part of the project
evidence and are not changed by the public cleanup.
