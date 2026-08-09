# performance-test/stage6b — Fault Drills

## What this does

`run_fault_drills.py` orchestrates four real failure drills against the
8-container local stack:

| drill | scenario |
|---|---|
| A | Gateway Sentinel precise hotspot rate limiting |
| B | Identity outage / circuit breaker / display vs required / recovery |
| C | Redis kill/remove/recreate + AOF/volume persistence + reconnect |
| D | MySQL-down accepted seckill → Pending retained → recovery persistence |

## Local output

Drill evidence (environment, per-drill `evidence.json`, aggregate
`summary.json`) is written under the gitignored local directory:

```text
.linklife-local/evidence/stage6b/
```

The public repository freezes the drill results summary in
`docs/reliability.md`; raw drill logs stay local and are not committed.

## Integrity note

Fault-drill correctness logic (admission semantics, exact Pending checks,
recovery gates) is part of the project evidence and is not changed by the
public cleanup.
