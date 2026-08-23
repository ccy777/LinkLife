"""将双机压测原始 JTL 与服务端核对结果汇总为可公开的逐轮 CSV。"""

from __future__ import annotations

import argparse
import csv
import importlib.util
import json
import statistics
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path


PUBLIC_FIELDS = [
    "scenario",
    "profile",
    "concurrency_or_users",
    "run",
    "samples",
    "successes",
    "failures",
    "error_rate_pct",
    "throughput_req_s",
    "p50_ms",
    "p95_ms",
    "p99_ms",
    "redis_get_per_request",
    "persisted_orders",
    "distinct_users",
    "duplicate_orders",
    "oversell",
    "stream_pending",
    "stream_dlq",
    "correctness_pass",
]


def parse_time(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def load_analyzer(repo_root: Path):
    analyzer_path = repo_root / "performance-test" / "stage6a" / "analyze_jtl.py"
    spec = importlib.util.spec_from_file_location("linklife_jtl_analyzer", analyzer_path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载 JTL 分析器：{analyzer_path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.analyze_jtl


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8-sig", newline="") as stream:
        return list(csv.DictReader(stream))


def redis_get_per_request(
    monitor_rows: list[dict[str, str]], start: datetime, end: datetime, samples: int
) -> str:
    window = [
        row
        for row in monitor_rows
        if start <= parse_time(row["timestamp"]) <= end
    ]
    if len(window) < 2 or samples <= 0:
        return ""
    delta = int(window[-1]["redis_cmdstat_get_calls"]) - int(
        window[0]["redis_cmdstat_get_calls"]
    )
    return f"{delta / samples:.8f}"


def analyze_window(jtl: Path, start: datetime, end: datetime, percentile) -> dict[str, object]:
    """只统计 run-meta 标记的正式测量窗口，排除文件中残留的预热或历史样本。"""
    start_ms = int(start.timestamp() * 1000)
    end_ms = int(end.timestamp() * 1000)
    elapsed: list[int] = []
    starts: list[int] = []
    ends: list[int] = []
    successes = 0
    failures = 0
    response_codes: Counter[str] = Counter()
    with jtl.open("r", encoding="utf-8-sig", newline="") as stream:
        for row in csv.DictReader(stream):
            try:
                timestamp = int(row.get("timeStamp", "0"))
                duration = int(row.get("elapsed", "0"))
            except ValueError:
                continue
            if timestamp < start_ms or timestamp > end_ms:
                continue
            elapsed.append(duration)
            starts.append(timestamp)
            ends.append(timestamp + duration)
            response_codes[str(row.get("responseCode", ""))] += 1
            if row.get("success", "").strip().lower() == "true":
                successes += 1
            else:
                failures += 1
    samples = successes + failures
    duration_seconds = (max(ends) - min(starts)) / 1000 if starts else 0.001
    return {
        "samples": samples,
        "successes": successes,
        "failures": failures,
        "error_rate_pct": round(failures / samples * 100, 4) if samples else 0,
        "throughput_req_s": round(samples / duration_seconds, 3),
        "p50_ms": percentile(elapsed, 0.50),
        "p95_ms": percentile(elapsed, 0.95),
        "p99_ms": percentile(elapsed, 0.99),
        "mean_ms": round(statistics.mean(elapsed), 3) if elapsed else 0,
        "response_codes": dict(response_codes),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--client-results", required=True, type=Path)
    parser.add_argument("--server-monitor", required=True, type=Path)
    parser.add_argument("--correctness", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[2]
    analyze_jtl = load_analyzer(repo_root)
    percentile = analyze_jtl.__globals__["percentile"]
    monitor_rows: list[dict[str, str]] = []
    for monitor in sorted(args.server_monitor.glob("server-monitor-*.csv")):
        monitor_rows.extend(read_csv(monitor))

    correctness_data = json.loads(args.correctness.read_text(encoding="utf-8"))
    correctness = {
        (int(item["level"]), int(item["run"])): item for item in correctness_data
    }
    rows: list[dict[str, object]] = []

    for jtl in sorted(args.client_results.glob("cache/*/[0-9]*/r*/result.jtl")):
        profile = jtl.parents[2].name
        concurrency = int(jtl.parents[1].name)
        if concurrency < 50:
            continue
        run = int(jtl.parent.name.removeprefix("r"))
        meta = json.loads((jtl.parent / "run-meta.json").read_text(encoding="utf-8-sig"))
        measured_start = parse_time(meta["measured_start_utc"])
        measured_end = parse_time(meta["measured_end_utc"])
        summary = analyze_window(jtl, measured_start, measured_end, percentile)
        rows.append(
            {
                "scenario": "hot-query",
                "profile": profile.upper(),
                "concurrency_or_users": concurrency,
                "run": run,
                "samples": summary["samples"],
                "successes": summary["successes"],
                "failures": summary["failures"],
                "error_rate_pct": summary["error_rate_pct"],
                "throughput_req_s": summary["throughput_req_s"],
                "p50_ms": summary["p50_ms"],
                "p95_ms": summary["p95_ms"],
                "p99_ms": summary["p99_ms"],
                "redis_get_per_request": redis_get_per_request(
                    monitor_rows,
                    measured_start,
                    measured_end,
                    int(summary["samples"]),
                ),
            }
        )

    for jtl in sorted(args.client_results.glob("seckill/[0-9]*/r*/result.jtl")):
        users = int(jtl.parents[1].name)
        if users < 300:
            continue
        run = int(jtl.parent.name.removeprefix("r"))
        meta = json.loads((jtl.parent / "run-meta.json").read_text(encoding="utf-8-sig"))
        summary = analyze_window(
            jtl,
            parse_time(meta["measured_start_utc"]),
            parse_time(meta["measured_end_utc"]),
            percentile,
        )
        state = correctness[(users, run)]
        persisted = int(state["mysql_orders"])
        distinct = int(state["mysql_distinct_users"])
        duplicates = int(state["mysql_duplicate_users"])
        stock = int(state["redis_stock"])
        passed = (
            summary["failures"] == 0
            and int(summary["successes"]) == users
            and persisted == users
            and distinct == users
            and duplicates == 0
            and stock == 0
            and int(state["stream_pending"]) == 0
            and int(state["stream_dlq"]) == 0
        )
        rows.append(
            {
                "scenario": "seckill",
                "profile": "BURST",
                "concurrency_or_users": users,
                "run": run,
                "samples": summary["samples"],
                "successes": summary["successes"],
                "failures": summary["failures"],
                "error_rate_pct": summary["error_rate_pct"],
                "throughput_req_s": summary["throughput_req_s"],
                "p50_ms": summary["p50_ms"],
                "p95_ms": summary["p95_ms"],
                "p99_ms": summary["p99_ms"],
                "persisted_orders": persisted,
                "distinct_users": distinct,
                "duplicate_orders": duplicates,
                "oversell": "false" if stock >= 0 and persisted <= users else "true",
                "stream_pending": state["stream_pending"],
                "stream_dlq": state["stream_dlq"],
                "correctness_pass": str(passed).lower(),
            }
        )

    rows.sort(
        key=lambda row: (
            str(row["scenario"]),
            int(row["concurrency_or_users"]),
            str(row["profile"]),
            int(row["run"]),
        )
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=PUBLIC_FIELDS, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)
    print(f"已生成 {len(rows)} 条正式结果：{args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
