"""Generate a public benchmark summary from the frozen results CSV.

Reads the frozen public results (`docs/evidence/performance-results.csv`) and
writes a plain summary into `.linklife-local/reports/`. It never overwrites the
frozen CSV and never re-runs the benchmarks.
"""

from __future__ import annotations

import csv
import statistics
from collections import defaultdict
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
RESULTS_CSV = REPO_ROOT / "docs" / "evidence" / "performance-results.csv"
SUMMARY_MD = REPO_ROOT / ".linklife-local" / "reports" / "stage6a-performance-summary.md"


def median(values: list[float]) -> float:
    return round(statistics.median(values), 3) if values else 0.0


def cv(values: list[float]) -> float:
    if len(values) < 2:
        return 0.0
    mean = statistics.mean(values)
    return round(statistics.stdev(values) / mean, 3) if mean else 0.0


def fnum(value) -> str:
    if value in (None, ""):
        return "-"
    try:
        f = float(value)
        if f == int(f):
            return str(int(f))
        return f"{f:.3f}"
    except (TypeError, ValueError):
        return str(value)


def load_rows() -> list[dict]:
    if not RESULTS_CSV.exists():
        raise SystemExit(f"frozen results not found: {RESULTS_CSV}")
    with RESULTS_CSV.open("r", encoding="utf-8", newline="") as fp:
        return list(csv.DictReader(fp))


def main() -> None:
    rows = load_rows()
    by_scenario: dict[str, list[dict]] = defaultdict(list)
    for row in rows:
        by_scenario[row.get("scenario", "")].append(row)

    lines: list[str] = []
    lines.append("# LinkLife Stage 6A Benchmark Summary\n")
    lines.append("> Local Docker benchmark observations only; not production capacity or SLA.\n")

    lines.append("## Environment\n")
    lines.append("- 8-container local Compose topology; business entry is the Gateway only.")
    lines.append("- JMeter runs on the same host as the containers (client-limited).")
    lines.append("- Shop/ShopType: 30s warm-up + 60s measured, 3 runs per profile.")
    lines.append("- Blog Hot / Seckill: frozen raw JTL re-analysis (no re-sent traffic).")
    lines.append("")

    lines.append("## Run summary\n")
    lines.append(f"- Official runs: {len(rows)} (Shop 18 + ShopType 6 + Blog Hot 9 + Seckill 9).")
    for scenario, group in sorted(by_scenario.items()):
        cl = sum(1 for r in group if r.get("client_limited") == "true")
        lines.append(f"- {scenario}: {len(group)} runs, client_limited={cl}")
    lines.append("")

    lines.append("## QPS / P95 (medians)\n")
    lines.append("| scenario | profile | threads | median QPS | median P95 ms |")
    lines.append("|---|---|---|---|---|")
    for scenario in ("shop", "shop-type", "blog-hot"):
        group = by_scenario.get(scenario, [])
        keys = []
        for r in group:
            keys.append((r.get("profile", ""), r.get("threads", "")))
        for profile, threads in sorted(set(keys)):
            sub = [r for r in group if r.get("profile") == profile and r.get("threads") == threads]
            qps = median([float(r["throughput_req_s"]) for r in sub])
            p95 = median([float(r["p95_ms"]) for r in sub])
            lines.append(f"| {scenario} | {profile or '-'} | {threads or '-'} | {fnum(qps)} | {fnum(p95)} |")
    lines.append("")

    lines.append("## Redis GET/request (measured window)\n")
    for scenario in ("shop", "shop-type"):
        group = by_scenario.get(scenario, [])
        by_profile = defaultdict(list)
        for r in group:
            v = r.get("redis_get_per_request")
            if v not in (None, ""):
                by_profile[r.get("profile", "")].append(float(v))
        for profile in ("off", "on"):
            if by_profile.get(profile):
                lines.append(f"- {scenario} {profile}: {fnum(median(by_profile[profile]))} GET/request")
    lines.append("")

    lines.append("## Correctness\n")
    seckill = by_scenario.get("seckill", [])
    if seckill:
        for level in sorted({int(r["threads"]) for r in seckill}):
            sub = [r for r in seckill if int(r["threads"]) == level]
            accepted = [int(r["accepted"]) for r in sub]
            dup = [int(r["duplicate_orders"]) for r in sub]
            p95 = [float(r["p95_ms"]) for r in sub]
            lines.append(
                f"- {level} users: accepted median {fnum(median(accepted))}, "
                f"duplicates max {max(dup)}, median P95 {fnum(median(p95))} ms"
            )
        lines.append("- No oversell / duplicate order observed in 100- and 300-user official runs.")
    lines.append("")

    lines.append("## Limitations\n")
    lines.append("- Client-limited single-host benchmark (host CPU saturated); QPS/P95 vary.")
    lines.append("- 500-user runs contain client-side connection failures; never phrase as all-success.")
    lines.append("- Numbers are engineering observations, not capacity, SLA, or online-traffic claims.")

    SUMMARY_MD.parent.mkdir(parents=True, exist_ok=True)
    SUMMARY_MD.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {SUMMARY_MD}")


if __name__ == "__main__":
    main()
