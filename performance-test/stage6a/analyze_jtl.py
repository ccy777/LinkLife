"""Stage6A JTL analyzer: raw JTL -> summary JSON/CSV.

Computes samples, duration, throughput, mean/p50/p95/p99/max latency,
error count/rate and response-code breakdown. No JMeter HTML dependence.

Throughput duration uses the sample end time:
    duration = max(timeStamp + elapsed) - min(timeStamp)
so the last sample's own elapsed time is included (burst runs are not
systematically overestimated).
"""

from __future__ import annotations

import argparse
import csv
import json
import statistics
import sys
import tempfile
from collections import Counter
from pathlib import Path


def percentile(values: list[int], p: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, int(round(len(ordered) * p)) - 1))
    return float(ordered[index])


def analyze_jtl(jtl_path: Path) -> dict:
    elapsed: list[int] = []
    starts: list[int] = []
    ends: list[int] = []
    ok = 0
    failed = 0
    codes: Counter[str] = Counter()
    with jtl_path.open("r", encoding="utf-8-sig", newline="") as fp:
        for row in csv.DictReader(fp):
            try:
                e = int(row.get("elapsed", "0"))
                t = int(row.get("timeStamp", "0"))
            except ValueError:
                continue
            elapsed.append(e)
            starts.append(t)
            ends.append(t + e)
            codes[str(row.get("responseCode", ""))] += 1
            if row.get("success", "").strip().lower() == "true":
                ok += 1
            else:
                failed += 1
    # Correct duration: first sample start -> last sample end (includes the
    # final sample's elapsed). Old algorithm used max(start)-min(start).
    duration_ms = (max(ends) - min(starts)) if starts else 0.0
    duration_s = duration_ms / 1000.0 if duration_ms > 0 else 0.001
    samples = ok + failed
    error_rate = (failed / samples * 100.0) if samples else 0.0
    return {
        "samples": samples,
        "successes": ok,
        "failures": failed,
        "error_rate_pct": round(error_rate, 4),
        "duration_seconds": round(duration_s, 3),
        "throughput_req_s": round(samples / duration_s, 3) if duration_s > 0 else 0.0,
        "mean_ms": round(statistics.mean(elapsed), 3) if elapsed else 0.0,
        "p50_ms": percentile(elapsed, 0.50),
        "p95_ms": percentile(elapsed, 0.95),
        "p99_ms": percentile(elapsed, 0.99),
        "max_ms": float(max(elapsed)) if elapsed else 0.0,
        "min_ms": float(min(elapsed)) if elapsed else 0.0,
        "response_codes": dict(sorted(codes.items())),
        "source_jtl": jtl_path.name,
    }


def run_self_test() -> int:
    """Standalone self-test that exercises the REAL analyze_jtl() path.

    Fixture (from the task spec):
        A: timeStamp=1000, elapsed=100, success=true, responseCode=200
        B: timeStamp=1500, elapsed=500, success=true, responseCode=200
    Correct analyzer result: duration_seconds=1.0, throughput_req_s=2.0.
    The old algorithm (max(start)-min(start)) would yield 0.5s / 4 req/s.
    The temporary JTL is created and removed automatically.
    """
    fd, tmp_name = tempfile.mkstemp(prefix="analyze-jtl-selftest-", suffix=".csv")
    tmp_jtl = Path(tmp_name)
    try:
        with open(fd, "w", encoding="utf-8", newline="") as fp:
            fp.write(
                "timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,"
                "success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect\n"
                "1000,100,fixture-A,200,OK,t1,text,true,,0,0,1,1,,100,0,0\n"
                "1500,500,fixture-B,200,OK,t2,text,true,,0,0,1,1,,500,0,0\n"
            )
        summary = analyze_jtl(tmp_jtl)
    finally:
        try:
            tmp_jtl.unlink(missing_ok=True)
        except OSError:
            pass

    checks = {
        "samples": summary.get("samples") == 2,
        "successes": summary.get("successes") == 2,
        "failures": summary.get("failures") == 0,
        "duration_seconds": abs(float(summary.get("duration_seconds", -1)) - 1.0) < 1e-9,
        "throughput_req_s": abs(float(summary.get("throughput_req_s", -1)) - 2.0) < 1e-9,
    }
    if not all(checks.values()):
        print(f"self-test FAILED: {summary} (expected duration_seconds=1.0, throughput_req_s=2.0)", file=sys.stderr)
        return 1
    print(
        "self-test OK: samples=2 successes=2 failures=0 duration_seconds=1.0 "
        "throughput_req_s=2.0 (old algorithm would give 0.5s / 4 req/s)"
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true", help="run the duration/throughput fixture test and exit")
    parser.add_argument("--jtl")
    parser.add_argument("--summary-json")
    parser.add_argument("--summary-csv")
    args = parser.parse_args()
    if args.self_test:
        return run_self_test()
    missing = [
        name for name, value in (
            ("--jtl", args.jtl),
            ("--summary-json", args.summary_json),
            ("--summary-csv", args.summary_csv),
        ) if not value
    ]
    if missing:
        parser.error("missing required arguments: " + ", ".join(missing))
    jtl = Path(args.jtl).resolve()
    if not jtl.exists():
        print(f"JTL not found: {jtl}", file=sys.stderr)
        return 2
    summary = analyze_jtl(jtl)
    out_json = Path(args.summary_json).resolve()
    out_csv = Path(args.summary_csv).resolve()
    out_json.parent.mkdir(parents=True, exist_ok=True)
    out_csv.parent.mkdir(parents=True, exist_ok=True)
    out_json.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    flat = {k: v for k, v in summary.items() if not isinstance(v, (dict, list))}
    with out_csv.open("w", newline="", encoding="utf-8") as fp:
        writer = csv.DictWriter(fp, fieldnames=sorted(flat.keys()))
        writer.writeheader()
        writer.writerow(flat)
    print(json.dumps(summary, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
