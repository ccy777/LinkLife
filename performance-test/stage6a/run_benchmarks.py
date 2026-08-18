"""Stage6A benchmark orchestrator.

Commands:
    gen-env          generate gitignored performance-test/stage6a.env with random dev passwords
    bootstrap        build/start linklife-stage6a stack, wait healthy, seed data, prepare tokens
    shop             Shop Detail Caffeine OFF/ON A/B (50/100/200 threads x 3 runs)
    shop-type        ShopType A/B (100 threads x 3 runs)
    blog             Blog Hot final baseline (25/50/100 threads x 3 runs)
    seckill          Seckill bursts (100/300/500 users x 3 runs) + convergence + correctness
    report           generate .linklife-local benchmark summary from frozen results
"""

from __future__ import annotations

import argparse
import base64
import csv
import datetime as _dt
import json
import os
import re
import secrets
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from typing import Optional

import pymysql
import redis
import requests

SCRIPT_DIR = Path(__file__).resolve().parent
PERF_DIR = SCRIPT_DIR.parent
REPO_ROOT = PERF_DIR.parent
COMPOSE_FILE = PERF_DIR / "deploy" / "docker-compose.stage6a.yml"
ENV_FILE = PERF_DIR / "stage6a.env"
EVIDENCE_ROOT = REPO_ROOT / ".linklife-local" / "evidence" / "stage6a"
RESULTS_CSV = REPO_ROOT / ".linklife-local" / "results" / "stage6a-results.csv"
JMX_DIR = SCRIPT_DIR / "jmeter"
TOKENS_CSV = PERF_DIR / "tokens.csv"

JMETER_HOME = Path(os.getenv("LINKLIFE_JMETER_HOME", str(Path(tempfile.gettempdir()) / "linklife-stage6a-tools" / "apache-jmeter-5.6.3")))
JMETER_BIN = Path(os.getenv("LINKLIFE_JMETER_BIN", str(JMETER_HOME / "bin" / "jmeter.bat")))

GATEWAY = "http://127.0.0.1:8080"
API = GATEWAY + "/api"
NACOS = "http://127.0.0.1:18848"
MYSQL_PORT = 13306
REDIS_PORT = 16379

ADMIN_PHONE = "13686869696"  # schema-seeded user id 1; used only to mint the admin token
SERVICE_NAMES = [
    "linklife-identity-service",
    "linklife-merchant-service",
    "linklife-transaction-service",
    "linklife-social-service",
]

CSV_FIELDS = [
    "scenario", "profile", "threads", "run", "run_id", "evidence_dir",
    "samples", "successes", "failures", "error_rate_pct", "throughput_req_s",
    "mean_ms", "p50_ms", "p95_ms", "p99_ms", "max_ms",
    "redis_get_delta", "redis_keyspace_hits_delta", "redis_keyspace_misses_delta",
    "redis_total_commands_delta", "redis_get_per_request", "http_429", "http_5xx",
    "docker_cpu_pct_avg", "docker_mem_pct_avg", "host_cpu_pct", "host_mem_pct",
    "seckill_voucher_id", "initial_stock", "accepted", "final_redis_stock",
    "mysql_orders", "distinct_users", "duplicate_orders", "redis_ordered_users",
    "stream_pending", "stream_dlq", "convergence_seconds", "correctness_pass",
    "client_limited", "status", "notes",
]


class BenchmarkFailure(Exception):
    pass


def now_stamp() -> str:
    return _dt.datetime.now().strftime("%Y%m%d-%H%M%S")


def load_env() -> dict:
    if not ENV_FILE.exists():
        raise RuntimeError(f"env file missing: {ENV_FILE}; run `gen-env` first")
    result: dict[str, str] = {}
    for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        result[key.strip()] = value.strip()
    return result


def set_env_value(key: str, value: str) -> None:
    text = ENV_FILE.read_text(encoding="utf-8")
    pattern = re.compile(rf"^{re.escape(key)}=.*$", re.MULTILINE)
    if pattern.search(text):
        text = pattern.sub(f"{key}={value}", text)
    else:
        text = text.rstrip() + f"\n{key}={value}\n"
    ENV_FILE.write_text(text, encoding="utf-8")


def run(cmd: list[str], timeout: int = 600, check: bool = True, cwd: Optional[Path] = None) -> subprocess.CompletedProcess:
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, cwd=str(cwd or REPO_ROOT))
    if check and result.returncode != 0:
        raise RuntimeError(f"command failed ({result.returncode}): {' '.join(cmd)}\n{result.stdout}\n{result.stderr}")
    return result


def compose(args: list[str], timeout: int = 600, check: bool = True) -> subprocess.CompletedProcess:
    cmd = ["docker", "compose", "--env-file", str(ENV_FILE), "-f", str(COMPOSE_FILE), *args]
    return run(cmd, timeout=timeout, check=check)


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def wait_http(url: str, timeout: int = 120, interval: float = 2.0) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            resp = requests.get(url, timeout=3)
            if resp.status_code < 500:
                return True
        except requests.RequestException:
            pass
        time.sleep(interval)
    return False


def nacos_services() -> list[str]:
    try:
        resp = requests.get(f"{NACOS}/nacos/v1/ns/service/list?pageNo=1&pageSize=50", timeout=5)
        if resp.status_code == 200:
            data = resp.json()
            names = data.get("doms") or data.get("services") or []
            return [str(n) for n in names]
    except (requests.RequestException, ValueError):
        pass
    return []


def wait_service_ready(service: str, timeout: int = 180) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            resp = requests.get(
                f"{NACOS}/nacos/v1/ns/instance/list?serviceName={service}&healthyOnly=true",
                timeout=5,
            )
            if resp.status_code == 200:
                data = resp.json()
                if isinstance(data.get("hosts"), list) and data["hosts"]:
                    return True
        except (requests.RequestException, ValueError):
            pass
        time.sleep(2)
    return False


def wait_gateway_ready(timeout: int = 120) -> bool:
    if not wait_http(f"{GATEWAY}/actuator/health", timeout=timeout):
        return False
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            resp = requests.get(f"{API}/shop/1", timeout=3)
            if resp.status_code == 200 and resp.json().get("success") is True:
                return True
        except (requests.RequestException, ValueError):
            pass
        time.sleep(2)
    return False


def restart_merchant(local_cache_enabled: bool, timeout: int = 180) -> None:
    set_env_value("LINKLIFE_LOCAL_CACHE_ENABLED", "true" if local_cache_enabled else "false")
    compose(["up", "-d", "--force-recreate", "merchant-service"], timeout=300)
    if not wait_service_ready("linklife-merchant-service", timeout=timeout):
        raise RuntimeError("merchant did not register in Nacos within timeout")
    if not wait_gateway_ready(timeout=timeout):
        raise RuntimeError("gateway -> merchant not ready after restart")


def redis_admin(env: dict) -> redis.Redis:
    return redis.Redis(
        host="127.0.0.1",
        port=REDIS_PORT,
        db=0,
        password=env["REDIS_ADMIN_PASSWORD"],
        decode_responses=True,
        socket_connect_timeout=5,
        socket_timeout=5,
    )


def mysql_conn(env: dict, database: str):
    return pymysql.connect(
        host="127.0.0.1",
        port=MYSQL_PORT,
        user="root",
        password=env["MYSQL_ROOT_PASSWORD"],
        database=database,
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
        autocommit=True,
    )


def redis_snapshot(env: dict) -> dict:
    client = redis_admin(env)
    try:
        raw = client.info("all")
    finally:
        client.close()
    parsed = {
        "total_commands_processed": int(raw.get("total_commands_processed", 0)),
        "keyspace_hits": int(raw.get("keyspace_hits", 0)),
        "keyspace_misses": int(raw.get("keyspace_misses", 0)),
    }
    # redis-py INFO returns a flat dict: cmdstat_* / total_commands_processed at top level.
    get_stat = raw.get("cmdstat_get", {})
    parsed["cmdstat_get_calls"] = int(get_stat.get("calls", 0)) if isinstance(get_stat, dict) else 0
    text = json.dumps(raw, ensure_ascii=False, indent=2)
    return {"parsed": parsed, "raw": text}


def snapshot_delta(before: dict, after: dict) -> dict:
    b = before["parsed"]
    a = after["parsed"]
    return {
        "total_commands_delta": a["total_commands_processed"] - b["total_commands_processed"],
        "keyspace_hits_delta": a["keyspace_hits"] - b["keyspace_hits"],
        "keyspace_misses_delta": a["keyspace_misses"] - b["keyspace_misses"],
        "cmdstat_get_delta": a["cmdstat_get_calls"] - b["cmdstat_get_calls"],
    }


def docker_stats_snapshot() -> dict:
    try:
        result = subprocess.run(
            ["docker", "stats", "--no-stream", "--format", "{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}|{{.MemPerc}}"],
            capture_output=True,
            text=True,
            timeout=60,
        )
    except subprocess.TimeoutExpired:
        return {"raw": "timeout", "rows": []}
    rows: list[dict] = []
    for line in result.stdout.splitlines():
        parts = [p.strip() for p in line.split("|")]
        if len(parts) != 4 or not parts[0].startswith("linklife-stage6a-"):
            continue
        cpu = float(parts[1].rstrip("%")) if parts[1].rstrip("%").replace(".", "", 1).isdigit() else 0.0
        mem = float(parts[3].rstrip("%")) if parts[3].rstrip("%").replace(".", "", 1).isdigit() else 0.0
        rows.append({"name": parts[0], "cpu_pct": cpu, "mem_pct": mem, "mem_usage": parts[2]})
    return {"raw": result.stdout, "rows": rows, "container_cores": round(sum(r["cpu_pct"] for r in rows) / 100.0, 3)}


def host_snapshot() -> dict:
    script = (
        "$samples = (Get-Counter '\\Processor(_Total)\\% Processor Time' -SampleInterval 1 -MaxSamples 3 "
        "-ErrorAction SilentlyContinue).CounterSamples.CookedValue;"
        "$cpu = 0;"
        "if ($samples) { $cpu = ($samples | Measure-Object -Average).Average };"
        "$os = Get-CimInstance Win32_OperatingSystem;"
        "$mem = 100 * (1 - $os.FreePhysicalMemory / $os.TotalVisibleMemorySize);"
        "$cs = Get-CimInstance Win32_ComputerSystem;"
        "Write-Output (('{0:N1}|{1:N1}|{2}') -f $cpu, $mem, $cs.NumberOfLogicalProcessors)"
    )
    try:
        result = subprocess.run(
            ["powershell", "-NoProfile", "-Command", script],
            capture_output=True,
            text=True,
            timeout=30,
        )
        line = (result.stdout or "").strip().splitlines()[-1] if result.stdout.strip() else "0|0|1"
        parts = line.split("|")
        cpu_s = parts[0] if len(parts) > 0 else "0"
        mem_s = parts[1] if len(parts) > 1 else "0"
        cores_s = parts[2] if len(parts) > 2 else "1"
        return {
            "cpu_pct": float(cpu_s),
            "mem_pct": float(mem_s),
            "logical_cores": int(float(cores_s)),
            "raw": result.stdout.strip(),
        }
    except Exception as exc:
        return {"cpu_pct": 0.0, "mem_pct": 0.0, "logical_cores": 1, "raw": f"error: {exc}"}


def jmeter_command(jmx: Path, props: dict, jtl: Path) -> list[str]:
    cmd = ["cmd.exe", "/c", str(JMETER_BIN), "-n", "-t", str(jmx), "-l", str(jtl)]
    for key, value in props.items():
        cmd.append(f"-J{key}={value}")
    return cmd


def run_jmeter_foreground(
    jmx: Path,
    props: dict,
    jtl: Path,
    stdout_file: Path,
    timeout: int,
) -> subprocess.Popen:
    jtl.parent.mkdir(parents=True, exist_ok=True)
    stdout_file.parent.mkdir(parents=True, exist_ok=True)
    fp = stdout_file.open("w", encoding="utf-8")
    proc = subprocess.Popen(
        jmeter_command(jmx, props, jtl),
        stdout=fp,
        stderr=subprocess.STDOUT,
        cwd=str(SCRIPT_DIR),
    )
    proc._stage6a_fp = fp  # type: ignore[attr-defined]
    proc._stage6a_timeout = timeout  # type: ignore[attr-defined]
    return proc


def wait_jmeter(proc: subprocess.Popen, jtl: Path) -> None:
    fp = getattr(proc, "_stage6a_fp", None)
    timeout = getattr(proc, "_stage6a_timeout", 300)
    try:
        proc.wait(timeout=timeout)
    finally:
        if fp is not None:
            fp.close()
    if proc.returncode != 0:
        raise RuntimeError(f"JMeter exited with {proc.returncode}; jtl={jtl}")
    if not jtl.exists() or jtl.stat().st_size == 0:
        raise RuntimeError(f"empty JTL: {jtl}")


def analyze_jtl(jtl: Path, run_dir: Path) -> dict:
    summary_json = run_dir / "summary.json"
    summary_csv = run_dir / "summary.csv"
    run(
        [
            "py",
            str(SCRIPT_DIR / "analyze_jtl.py"),
            "--jtl", str(jtl),
            "--summary-json", str(summary_json),
            "--summary-csv", str(summary_csv),
        ],
        timeout=120,
    )
    return json.loads(summary_json.read_text(encoding="utf-8"))


def http_errors(summary: dict) -> tuple[int, int]:
    codes = summary.get("response_codes", {})
    return int(codes.get("429", 0)), sum(int(v) for k, v in codes.items() if k.startswith("5"))


def client_side_failures(summary: dict) -> int:
    codes = summary.get("response_codes", {})
    return sum(int(v) for k, v in codes.items() if str(k).startswith("Non HTTP response code"))


def append_results(row: dict) -> None:
    RESULTS_CSV.parent.mkdir(parents=True, exist_ok=True)
    new_file = not RESULTS_CSV.exists()
    with RESULTS_CSV.open("a", newline="", encoding="utf-8") as fp:
        writer = csv.DictWriter(fp, fieldnames=CSV_FIELDS, extrasaction="ignore")
        if new_file:
            writer.writeheader()
        writer.writerow(row)


def read_results_rows() -> list[dict]:
    if not RESULTS_CSV.exists():
        return []
    with RESULTS_CSV.open("r", encoding="utf-8", newline="") as fp:
        return list(csv.DictReader(fp))


def write_results_rows(rows: list[dict]) -> None:
    RESULTS_CSV.parent.mkdir(parents=True, exist_ok=True)
    with RESULTS_CSV.open("w", newline="", encoding="utf-8") as fp:
        writer = csv.DictWriter(fp, fieldnames=CSV_FIELDS, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def replace_scenario_rows(scenario: str) -> None:
    """Atomically drop rows of one scenario so re-running a scenario does not append duplicates."""
    rows = [r for r in read_results_rows() if r.get("scenario") != scenario]
    write_results_rows(rows)


def make_row(**kwargs) -> dict:
    return {field: "" for field in CSV_FIELDS} | kwargs


def env_capture(env: dict) -> str:
    lines = [
        f"captured_at: {_dt.datetime.now().isoformat(timespec='seconds')}",
        f"git_branch: {run(['git', 'branch', '--show-current'], check=False).stdout.strip()}",
        f"git_commit: {run(['git', 'rev-parse', 'HEAD'], check=False).stdout.strip()}",
    ]
    for label, cmd in [
        ("os", ["powershell", "-NoProfile", "-Command", "(Get-CimInstance Win32_OperatingSystem | Select-Object Caption,Version | Out-String).Trim()"]),
        ("cpu", ["powershell", "-NoProfile", "-Command", "(Get-CimInstance Win32_Processor | Select-Object Name,NumberOfLogicalProcessors | Out-String).Trim()"]),
        ("memory", ["powershell", "-NoProfile", "-Command", "$os = Get-CimInstance Win32_OperatingSystem; 'TotalVisibleMemorySize=' + $os.TotalVisibleMemorySize"]),
    ]:
        try:
            stdout = run(cmd, check=False).stdout
            cleaned = "\n".join(line.rstrip() for line in stdout.splitlines()).strip()
            lines.append(f"{label}: {cleaned}")
        except Exception as exc:
            lines.append(f"{label}: error {exc}")
    try:
        lines.append(f"docker_version: {run(['docker', '--version'], check=False).stdout.strip()}")
        lines.append(f"docker_compose_version: {run(['docker', 'compose', 'version'], check=False).stdout.strip()}")
    except Exception as exc:
        lines.append(f"docker: error {exc}")
    try:
        lines.append(f"java_version: {run(['java', '-version'], check=False).stderr.strip().splitlines()[0]}")
    except Exception as exc:
        lines.append(f"java: error {exc}")
    try:
        jmeter_out = run([str(JMETER_BIN), '--version'], check=False).stderr + run([str(JMETER_BIN), '--version'], check=False).stdout
        match = re.search(r'\b\d+\.\d+(\.\d+)?\b', jmeter_out)
        lines.append(f"jmeter_version: Apache JMeter {match.group(0) if match else 'unknown'}")
    except Exception as exc:
        lines.append(f"jmeter: error {exc}")
    try:
        lines.append(f"python_version: {run(['py', '--version'], check=False).stdout.strip()}")
    except Exception as exc:
        lines.append(f"python: error {exc}")
    try:
        images = run(["docker", "images", "--format", "{{.Repository}}:{{.Tag}}", "linklife-stage6a-*"], check=False).stdout.strip()
        lines.append(f"stage6a_images: {images}")
    except Exception as exc:
        lines.append(f"images: error {exc}")
    lines.append("per_container_hard_limit: not set (default compose resources, same as Stage 4 profile)")
    return "\n".join(lines) + "\n"


def cmd_gen_env(args: argparse.Namespace) -> int:
    if ENV_FILE.exists() and not args.force:
        print(f"env file already exists: {ENV_FILE}; use --force to regenerate")
        return 0

    def secret(n: int = 16) -> str:
        return secrets.token_hex(n)

    values = {
        "MYSQL_ROOT_PASSWORD": secret(),
        "MYSQL_IDENTITY_PASSWORD": secret(),
        "MYSQL_MERCHANT_PASSWORD": secret(),
        "MYSQL_TRANSACTION_PASSWORD": secret(),
        "MYSQL_SOCIAL_PASSWORD": secret(),
        "REDIS_ADMIN_PASSWORD": secret(),
        "REDIS_IDENTITY_PASSWORD": secret(),
        "REDIS_MERCHANT_PASSWORD": secret(),
        "REDIS_TRANSACTION_PASSWORD": secret(),
        "REDIS_SOCIAL_PASSWORD": secret(),
        "REDIS_GATEWAY_PASSWORD": secret(),
        # Nacos 3.0.3 token manager requires standard Base64 (>=32 bytes);
        # URL-safe base64 may contain '-' which Java's decoder rejects.
        "NACOS_AUTH_TOKEN": base64.b64encode(secrets.token_bytes(32)).decode(),
        "NACOS_AUTH_IDENTITY_KEY": secret(8),
        "NACOS_AUTH_IDENTITY_VALUE": secret(8),
        "LINKLIFE_ADMIN_USER_IDS": "1",
        "LINKLIFE_SENTINEL_GATEWAY_ENABLED": "false",
        "LINKLIFE_SENTINEL_HOT_BLOG_QPS": "100",
        "LINKLIFE_SENTINEL_SHOP_OF_TYPE_QPS": "100",
        "LINKLIFE_SENTINEL_SECKILL_QPS": "50",
        "LINKLIFE_SENTINEL_IDENTITY_ENABLED": "true",
        "LINKLIFE_SENTINEL_IDENTITY_EXCEPTION_RATIO": "0.5",
        "LINKLIFE_SENTINEL_IDENTITY_MIN_REQUEST_AMOUNT": "5",
        "LINKLIFE_SENTINEL_IDENTITY_STAT_INTERVAL_MS": "10000",
        "LINKLIFE_SENTINEL_IDENTITY_TIME_WINDOW_SECONDS": "5",
        "LINKLIFE_LOCAL_CACHE_ENABLED": "true",
        "LINKLIFE_LOCAL_CACHE_SHOP_MAXIMUM_SIZE": "10000",
        "LINKLIFE_LOCAL_CACHE_SHOP_TTL_SECONDS": "10",
        "LINKLIFE_LOCAL_CACHE_SHOP_TYPE_MAXIMUM_SIZE": "16",
        "LINKLIFE_LOCAL_CACHE_SHOP_TYPE_TTL_SECONDS": "60",
    }
    lines = ["# Stage6A private env (auto-generated, gitignored; do not print)", ""]
    for key, value in values.items():
        lines.append(f"{key}={value}")
    ENV_FILE.write_text("\n".join(lines) + "\n", encoding="utf-8")
    os.chmod(ENV_FILE, 0o600)
    print(f"generated {ENV_FILE} (permissions 600, secrets not printed)")
    return 0


def cmd_bootstrap(args: argparse.Namespace) -> int:
    env = load_env()
    if not args.skip_build:
        compose(["build"], timeout=1800)
    compose(["up", "-d"], timeout=600)
    print("waiting for stack readiness...")
    if not wait_http(f"{NACOS}/nacos/v1/ns/service/list?pageNo=1&pageSize=5", timeout=180):
        raise RuntimeError("Nacos not healthy within 180s")
    for service in SERVICE_NAMES:
        if not wait_service_ready(service, timeout=180):
            raise RuntimeError(f"{service} not registered within 180s")
    if not wait_gateway_ready(timeout=120):
        raise RuntimeError("gateway not ready within 120s")
    run(
        [
            "py", str(SCRIPT_DIR / "seed_data.py"),
            "--count", str(args.users),
            "--mysql-password", env["MYSQL_ROOT_PASSWORD"],
        ],
        timeout=300,
    )
    if not args.skip_tokens:
        run(
            [
                "py", str(SCRIPT_DIR / "prepare_tokens.py"),
                "--limit", str(args.tokens),
                "--workers", "10",
                "--mysql-password", env["MYSQL_ROOT_PASSWORD"],
                "--redis-password", env["REDIS_ADMIN_PASSWORD"],
            ],
            timeout=900,
        )
    evidence = EVIDENCE_ROOT / "environment.txt"
    write_text(evidence, env_capture(env))
    print(f"bootstrap complete; environment captured at {evidence}")
    return 0


def run_sustained_scenario(
    env: dict,
    scenario: str,
    jmx: Path,
    profile: str,
    threads: int,
    run_index: int,
    warmup_seconds: int,
    measured_seconds: int,
) -> dict:
    run_id = f"{scenario}-{profile}-{threads}-r{run_index}" if profile else f"{scenario}-{threads}-r{run_index}"
    run_dir = EVIDENCE_ROOT / scenario / run_id
    run_dir.mkdir(parents=True, exist_ok=True)

    warmup_before_redis = redis_snapshot(env)
    warmup_before_docker = docker_stats_snapshot()
    warmup_before_host = host_snapshot()

    warmup_jtl = run_dir / "warmup.jtl"
    warmup_stdout = run_dir / "warmup-stdout.txt"
    warmup_proc = run_jmeter_foreground(
        jmx,
        {"threads": threads, "rampUp": 10, "duration": warmup_seconds},
        warmup_jtl,
        warmup_stdout,
        timeout=warmup_seconds + 120,
    )
    time.sleep(min(30, max(5, warmup_seconds // 2)))
    mid_docker = docker_stats_snapshot()
    mid_host = host_snapshot()
    wait_jmeter(warmup_proc, warmup_jtl)

    # Official Redis delta must cover ONLY the measured window: snapshot after
    # warm-up completes and immediately before the measured run starts.
    measured_before_redis = redis_snapshot(env)
    measured_before_docker = docker_stats_snapshot()
    measured_before_host = host_snapshot()

    measured_jtl = run_dir / "result.jtl"
    measured_stdout = run_dir / "jmeter-stdout.txt"
    measured_proc = run_jmeter_foreground(
        jmx,
        {"threads": threads, "rampUp": 10, "duration": measured_seconds},
        measured_jtl,
        measured_stdout,
        timeout=measured_seconds + 120,
    )
    time.sleep(min(30, max(5, measured_seconds // 2)))
    mid_measured_docker = docker_stats_snapshot()
    mid_measured_host = host_snapshot()
    wait_jmeter(measured_proc, measured_jtl)

    measured_after_redis = redis_snapshot(env)
    measured_after_docker = docker_stats_snapshot()
    measured_after_host = host_snapshot()
    measured_delta = snapshot_delta(measured_before_redis, measured_after_redis)
    warmup_delta = snapshot_delta(warmup_before_redis, measured_before_redis)

    summary = analyze_jtl(measured_jtl, run_dir)
    http_429, http_5xx = http_errors(summary)

    write_text(run_dir / "redis-warmup-before.json", warmup_before_redis["raw"])
    write_text(run_dir / "redis-warmup-delta.json", json.dumps(warmup_delta, ensure_ascii=False, indent=2))
    write_text(run_dir / "redis-measured-before.json", measured_before_redis["raw"])
    write_text(run_dir / "redis-measured-after.json", measured_after_redis["raw"])
    write_text(run_dir / "redis-measured-delta.json", json.dumps(measured_delta, ensure_ascii=False, indent=2))
    write_text(run_dir / "docker-stats.txt", "\n\n---\n\n".join([
        "BEFORE(warmup):\n" + warmup_before_docker["raw"],
        "MID(warmup):\n" + mid_docker["raw"],
        "BEFORE(measured):\n" + measured_before_docker["raw"],
        "MID(measured):\n" + mid_measured_docker["raw"],
        "AFTER(measured):\n" + measured_after_docker["raw"],
    ]))
    write_text(run_dir / "host.txt", json.dumps({
        "before_warmup": warmup_before_host,
        "before_measured": measured_before_host,
        "mid_warmup": mid_host,
        "mid_measured": mid_measured_host,
        "after_measured": measured_after_host,
    }, ensure_ascii=False, indent=2))
    write_text(run_dir / "stdout.txt", measured_stdout.read_text(encoding="utf-8", errors="replace"))

    avg_cpu = (
        sum(r["cpu_pct"] for r in mid_measured_docker["rows"]) / len(mid_measured_docker["rows"])
        if mid_measured_docker["rows"] else 0.0
    )
    avg_mem = (
        sum(r["mem_pct"] for r in mid_measured_docker["rows"]) / len(mid_measured_docker["rows"])
        if mid_measured_docker["rows"] else 0.0
    )
    host_cpu = mid_measured_host.get("cpu_pct", 0.0)
    host_mem = mid_measured_host.get("mem_pct", 0.0)
    samples = summary["samples"]
    container_cores = float(mid_measured_docker.get("container_cores", 0.0))
    logical_cores = int(mid_measured_host.get("logical_cores", 1))
    client_limited = host_cpu >= 90 and container_cores < logical_cores * 0.7
    notes = "client-limited (host CPU saturated, containers not)" if client_limited else ""
    client_failures = client_side_failures(summary)
    if client_failures:
        client_limited = True
        notes += (f" client-side connect failures={client_failures} "
                  f"(JMeter/Windows ephemeral port exhaustion; requests never reached gateway)")
    if http_429:
        notes += " 429 detected"
    if http_5xx:
        notes += " 5xx detected"

    row = make_row(
        scenario=scenario,
        profile=profile or "",
        threads=threads,
        run=run_index,
        run_id=run_id,
        evidence_dir=str(run_dir.relative_to(REPO_ROOT)).replace("\\", "/"),
        samples=samples,
        successes=summary["successes"],
        failures=summary["failures"],
        error_rate_pct=summary["error_rate_pct"],
        throughput_req_s=summary["throughput_req_s"],
        mean_ms=summary["mean_ms"],
        p50_ms=summary["p50_ms"],
        p95_ms=summary["p95_ms"],
        p99_ms=summary["p99_ms"],
        max_ms=summary["max_ms"],
        redis_get_delta=measured_delta["cmdstat_get_delta"],
        redis_keyspace_hits_delta=measured_delta["keyspace_hits_delta"],
        redis_keyspace_misses_delta=measured_delta["keyspace_misses_delta"],
        redis_total_commands_delta=measured_delta["total_commands_delta"],
        redis_get_per_request=round(measured_delta["cmdstat_get_delta"] / samples, 4) if samples else 0,
        http_429=http_429,
        http_5xx=http_5xx,
        docker_cpu_pct_avg=round(avg_cpu, 2),
        docker_mem_pct_avg=round(avg_mem, 2),
        host_cpu_pct=host_cpu,
        host_mem_pct=host_mem,
        client_limited="true" if client_limited else "false",
        status="done",
        notes=notes,
    )
    append_results(row)
    print(json.dumps({k: v for k, v in row.items() if v != ""}, ensure_ascii=False, indent=2))
    return row


def cmd_shop(args: argparse.Namespace) -> int:
    env = load_env()
    replace_scenario_rows("shop")
    jmx = JMX_DIR / "shop-detail.jmx"
    # background noise window (no traffic, same length as a measured window)
    b = redis_snapshot(env)
    time.sleep(args.window_seconds)
    a = redis_snapshot(env)
    delta = snapshot_delta(b, a)
    write_text(
        EVIDENCE_ROOT / "redis-commandstats" / "background-window.json",
        json.dumps({"window_seconds": args.window_seconds, "delta": delta, "before": b["parsed"], "after": a["parsed"]}, ensure_ascii=False, indent=2),
    )
    print(f"background window delta (no traffic, {args.window_seconds}s): {delta}")

    for threads in (50, 100, 200):
        for profile, enabled in (("off", False), ("on", True)):
            for run_index in (1, 2, 3):
                print(f"[shop] profile={profile} threads={threads} run={run_index} restarting merchant...")
                restart_merchant(enabled)
                # warm Redis L2 (and L1 when enabled)
                for _ in range(3):
                    try:
                        requests.get(f"{API}/shop/1", timeout=5)
                    except requests.RequestException:
                        pass
                row = run_sustained_scenario(
                    env, "shop", jmx, profile, threads, run_index,
                    warmup_seconds=args.warmup_seconds,
                    measured_seconds=args.measured_seconds,
                )
                if float(row["error_rate_pct"]) > 1.0:
                    print(f"[shop] {row['run_id']} error_rate={row['error_rate_pct']}% > 1%; evidence kept")
                if row["client_limited"] == "true":
                    print(f"[shop] {row['run_id']} client-limited: host CPU {row['host_cpu_pct']}% "
                          f"vs container cores {row['docker_cpu_pct_avg']}% avg")
    return 0


def cmd_shop_type(args: argparse.Namespace) -> int:
    env = load_env()
    replace_scenario_rows("shop-type")
    jmx = JMX_DIR / "shop-type.jmx"
    for profile, enabled in (("off", False), ("on", True)):
        print(f"[shop-type] profile={profile} restarting merchant...")
        restart_merchant(enabled)
        for _ in range(3):
            try:
                requests.get(f"{API}/shop-type/list", timeout=5)
            except requests.RequestException:
                pass
        for run_index in (1, 2, 3):
            row = run_sustained_scenario(
                env, "shop-type", jmx, profile, 100, run_index,
                warmup_seconds=args.warmup_seconds,
                measured_seconds=args.measured_seconds,
            )
            if float(row["error_rate_pct"]) > 1.0:
                print("shop-type error rate > 1%; recording and stopping further runs")
                return 3
    return 0


def cmd_blog(args: argparse.Namespace) -> int:
    env = load_env()
    replace_scenario_rows("blog-hot")
    jmx = JMX_DIR / "blog-hot.jmx"
    for threads in (25, 50, 100):
        for run_index in (1, 2, 3):
            row = run_sustained_scenario(
                env, "blog-hot", jmx, "", threads, run_index,
                warmup_seconds=args.warmup_seconds,
                measured_seconds=args.measured_seconds,
            )
            if int(row["http_429"]) or int(row["http_5xx"]) or float(row["error_rate_pct"]) > 1.0:
                print(f"blog-hot failure evidence at threads={threads}: 429={row['http_429']} 5xx={row['http_5xx']} error={row['error_rate_pct']}%")
                return 3
    return 0


def read_tokens(limit: Optional[int]) -> list[tuple[str, str]]:
    if not TOKENS_CSV.exists():
        raise RuntimeError(f"tokens csv missing: {TOKENS_CSV}")
    rows: list[tuple[str, str]] = []
    with TOKENS_CSV.open("r", encoding="utf-8-sig", newline="") as fp:
        for row in csv.DictReader(fp):
            phone = row.get("phone", "").strip()
            token = row.get("token", "").strip()
            if phone and token:
                rows.append((phone, token))
            if limit is not None and len(rows) >= limit:
                break
    if limit is not None and len(rows) < limit:
        raise RuntimeError(f"tokens available {len(rows)} < required {limit}")
    return rows


def admin_token(env: dict) -> str:
    for phone, token in read_tokens(None):
        if phone == ADMIN_PHONE:
            return token
    raise RuntimeError(f"admin token for phone {ADMIN_PHONE} not found in tokens.csv")


def create_voucher(env: dict, stock: int, title: str) -> int:
    token = admin_token(env)
    # Service containers run in UTC; send naive UTC wall-clock strings so the
    # container-side LocalDateTime -> epoch conversion matches admission time.
    now_utc = _dt.datetime.now(_dt.timezone.utc).replace(microsecond=0)
    begin_time = (now_utc - _dt.timedelta(minutes=1)).strftime("%Y-%m-%dT%H:%M:%S")
    end_time = (now_utc + _dt.timedelta(hours=2)).strftime("%Y-%m-%dT%H:%M:%S")
    body = {
        "shopId": 1,
        "title": title,
        "subTitle": "Stage6A benchmark voucher",
        "rules": "test-only",
        "payValue": 100,
        "actualValue": 1000,
        "type": 1,
        "status": 1,
        "stock": stock,
        "beginTime": begin_time,
        "endTime": end_time,
    }
    resp = requests.post(f"{API}/voucher/seckill", headers={"Authorization": token}, json=body, timeout=15)
    resp.raise_for_status()
    data = resp.json()
    if data.get("success") is not True:
        raise RuntimeError(f"voucher create rejected: {data.get('errorMsg')}")
    try:
        return int(data["data"])
    except (TypeError, ValueError) as exc:
        raise RuntimeError(f"invalid voucherId response: {data}") from exc


def wait_voucher_initialized(env: dict, voucher_id: int, stock: int, timeout: int = 60) -> bool:
    client = redis_admin(env)
    try:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            raw = client.get(f"transaction:seckill:stock:{voucher_id}")
            if raw is not None and str(raw) == str(stock):
                return True
            time.sleep(1)
    finally:
        client.close()
    return False


def seckill_state(env: dict, voucher_id: int) -> dict:
    client = redis_admin(env)
    try:
        raw_stock = client.get(f"transaction:seckill:stock:{voucher_id}")
        ordered = client.scard(f"transaction:seckill:order:{voucher_id}")
        pending = 0
        try:
            pending_info = client.xpending("transaction:stream.orders", "g1")
            if isinstance(pending_info, dict):
                pending = int(pending_info.get("pending", 0))
            elif pending_info:
                pending = int(pending_info[0])
        except redis.ResponseError:
            pending = -1
        dlq = client.xlen("transaction:stream.orders.dlq")
    finally:
        client.close()
    with mysql_conn(env, "linklife_transaction") as conn, conn.cursor() as cursor:
        cursor.execute(
            "SELECT COUNT(*) AS orders, COUNT(DISTINCT user_id) AS users FROM tb_voucher_order WHERE voucher_id=%s",
            (voucher_id,),
        )
        row = cursor.fetchone()
        cursor.execute(
            "SELECT COUNT(*) AS c FROM (SELECT user_id FROM tb_voucher_order WHERE voucher_id=%s "
            "GROUP BY user_id HAVING COUNT(*) > 1) t",
            (voucher_id,),
        )
        dup = cursor.fetchone()
    return {
        "redis_stock": int(raw_stock) if raw_stock is not None else -1,
        "redis_ordered_users": int(ordered),
        "mysql_orders": int(row["orders"]),
        "mysql_distinct_users": int(row["users"]),
        "mysql_duplicate_users": int(dup["c"]),
        "stream_pending": pending,
        "stream_dlq": int(dlq),
    }


def wait_convergence(env: dict, voucher_id: int, accepted: int, timeout: int = 60) -> tuple[dict, float]:
    start = time.monotonic()
    state = seckill_state(env, voucher_id)
    while time.monotonic() - start < timeout:
        if state["mysql_orders"] >= accepted and state["stream_pending"] == 0:
            return state, round(time.monotonic() - start, 2)
        time.sleep(2)
        state = seckill_state(env, voucher_id)
    return state, round(time.monotonic() - start, 2)


def cmd_seckill(args: argparse.Namespace) -> int:
    env = load_env()
    replace_scenario_rows("seckill")
    jmx = JMX_DIR / "seckill.jmx"
    read_tokens(args.levels[-1])
    partial = False
    for level in args.levels:
        for run_index in (1, 2, 3):
            run_id = f"seckill-{level}-r{run_index}"
            run_dir = EVIDENCE_ROOT / "seckill" / run_id
            run_dir.mkdir(parents=True, exist_ok=True)
            title = f"stage6a-seckill-{now_stamp()}-{level}-{run_index}"
            print(f"[seckill] {run_id} creating voucher stock={level} ...")
            voucher_id = create_voucher(env, level, title)
            if not wait_voucher_initialized(env, voucher_id, level):
                raise BenchmarkFailure(f"voucher {voucher_id} Redis init timeout; evidence kept")
            print(f"[seckill] {run_id} voucherId={voucher_id} initialized")

            tokens = read_tokens(level)
            subset = PERF_DIR / "output" / f"tokens-{voucher_id}.csv"
            subset.parent.mkdir(parents=True, exist_ok=True)
            with subset.open("w", newline="", encoding="utf-8") as fp:
                writer = csv.writer(fp)
                writer.writerow(["phone", "token"])
                writer.writerows(tokens)

            before_redis = redis_snapshot(env)
            before_docker = docker_stats_snapshot()
            before_host = host_snapshot()
            jtl = run_dir / "result.jtl"
            stdout_file = run_dir / "jmeter-stdout.txt"
            proc = run_jmeter_foreground(
                jmx,
                {
                    "threads": level,
                    "rampUp": args.seckill_ramp_up,
                    "voucherId": voucher_id,
                    "csvFile": str(subset),
                },
                jtl,
                stdout_file,
                timeout=180,
            )
            time.sleep(min(20, max(5, args.seckill_ramp_up + 5)))
            mid_docker = docker_stats_snapshot()
            mid_host = host_snapshot()
            wait_jmeter(proc, jtl)
            after_redis = redis_snapshot(env)
            after_docker = docker_stats_snapshot()
            after_host = host_snapshot()
            delta = snapshot_delta(before_redis, after_redis)
            summary = analyze_jtl(jtl, run_dir)
            accepted = summary["successes"]
            http_429, http_5xx = http_errors(summary)
            client_failures = client_side_failures(summary)

            state, convergence = wait_convergence(env, voucher_id, accepted)
            expected_stock = level - accepted
            correctness = all([
                state["redis_stock"] >= 0,
                state["mysql_orders"] == accepted,
                state["mysql_distinct_users"] == accepted,
                state["mysql_duplicate_users"] == 0,
                state["mysql_orders"] <= level,
                state["redis_ordered_users"] == accepted,
                state["stream_pending"] == 0,
                state["redis_stock"] == expected_stock,
            ])
            notes = []
            if http_429:
                notes.append("429 detected")
            if http_5xx:
                notes.append("5xx detected")
            if client_failures:
                notes.append(
                    f"client-side connect failures={client_failures} "
                    f"(JMeter/Windows ephemeral port exhaustion; requests never reached gateway)"
                )
            if not correctness:
                notes.append("CORRECTNESS FAIL")

            write_text(run_dir / "state.json", json.dumps({
                "voucher_id": voucher_id,
                "initial_stock": level,
                "accepted": accepted,
                "summary": summary,
                "state": state,
                "convergence_seconds": convergence,
                "correctness_pass": correctness,
                "redis_delta": delta,
            }, ensure_ascii=False, indent=2))
            write_text(run_dir / "redis-before.json", before_redis["raw"])
            write_text(run_dir / "redis-after.json", after_redis["raw"])
            write_text(run_dir / "docker-stats.txt", "\n\n---\n\n".join([
                "BEFORE:\n" + before_docker["raw"],
                "MID:\n" + mid_docker["raw"],
                "AFTER:\n" + after_docker["raw"],
            ]))
            write_text(run_dir / "host.txt", json.dumps({
                "before": before_host, "mid": mid_host, "after": after_host,
            }, ensure_ascii=False, indent=2))

            avg_cpu = sum(r["cpu_pct"] for r in mid_docker["rows"]) / len(mid_docker["rows"]) if mid_docker["rows"] else 0.0
            avg_mem = sum(r["mem_pct"] for r in mid_docker["rows"]) / len(mid_docker["rows"]) if mid_docker["rows"] else 0.0
            host_cpu = mid_host.get("cpu_pct", 0.0)
            client_limited = (host_cpu >= 90 and avg_cpu < 90) or client_failures > 0

            row = make_row(
                scenario="seckill",
                profile="burst",
                threads=level,
                run=run_index,
                run_id=run_id,
                evidence_dir=str(run_dir.relative_to(REPO_ROOT)).replace("\\", "/"),
                samples=summary["samples"],
                successes=accepted,
                failures=summary["failures"],
                error_rate_pct=summary["error_rate_pct"],
                throughput_req_s=summary["throughput_req_s"],
                mean_ms=summary["mean_ms"],
                p50_ms=summary["p50_ms"],
                p95_ms=summary["p95_ms"],
                p99_ms=summary["p99_ms"],
                max_ms=summary["max_ms"],
                redis_get_delta=delta["cmdstat_get_delta"],
                redis_keyspace_hits_delta=delta["keyspace_hits_delta"],
                redis_keyspace_misses_delta=delta["keyspace_misses_delta"],
                redis_total_commands_delta=delta["total_commands_delta"],
                http_429=http_429,
                http_5xx=http_5xx,
                docker_cpu_pct_avg=round(avg_cpu, 2),
                docker_mem_pct_avg=round(avg_mem, 2),
                host_cpu_pct=host_cpu,
                host_mem_pct=mid_host.get("mem_pct", 0.0),
                seckill_voucher_id=voucher_id,
                initial_stock=level,
                accepted=accepted,
                final_redis_stock=state["redis_stock"],
                mysql_orders=state["mysql_orders"],
                distinct_users=state["mysql_distinct_users"],
                duplicate_orders=state["mysql_duplicate_users"],
                redis_ordered_users=state["redis_ordered_users"],
                stream_pending=state["stream_pending"],
                stream_dlq=state["stream_dlq"],
                convergence_seconds=convergence,
                correctness_pass="true" if correctness else "false",
                client_limited="true" if client_limited else "false",
                status="done",
                notes="; ".join(notes),
            )
            append_results(row)
            print(json.dumps({k: v for k, v in row.items() if v != ""}, ensure_ascii=False, indent=2))
            subset.unlink(missing_ok=True)

            if not correctness or http_429 or http_5xx:
                print(f"[seckill] {run_id} FAILED correctness/errors; keeping evidence; stopping seckill phase")
                partial = True
                return 3 if partial else 0
    return 0


def cmd_report(args: argparse.Namespace) -> int:
    run(["py", str(SCRIPT_DIR / "generate_report.py")], timeout=300)
    return 0


def cmd_reanalyze(args: argparse.Namespace) -> int:
    """Re-analyze existing Blog/Seckill JTLs with the corrected analyzer.

    No traffic is sent. JTL-derived fields are recomputed; seckill correctness
    fields come from the preserved state.json/evidence and are not rewritten.
    """
    existing = {r.get("run_id", ""): r for r in read_results_rows()}
    rebuilt: list[dict] = []

    for jtl in sorted((EVIDENCE_ROOT / "blog-hot").glob("*/result.jtl")):
        run_dir = jtl.parent
        run_id = run_dir.name
        summary = analyze_jtl(jtl, run_dir)
        old = dict(existing.get(run_id, make_row()))
        row = dict(old)
        for key in ("samples", "successes", "failures", "error_rate_pct", "throughput_req_s",
                    "mean_ms", "p50_ms", "p95_ms", "p99_ms", "max_ms"):
            row[key] = summary[key]
        http_429, http_5xx = http_errors(summary)
        row["http_429"] = http_429
        row["http_5xx"] = http_5xx
        row["scenario"] = "blog-hot"
        row["evidence_dir"] = str(run_dir.relative_to(REPO_ROOT)).replace("\\", "/")
        row["status"] = old.get("status") or "done"
        rebuilt.append(row)

    for jtl in sorted((EVIDENCE_ROOT / "seckill").glob("*/result.jtl")):
        run_dir = jtl.parent
        run_id = run_dir.name
        summary = analyze_jtl(jtl, run_dir)
        old = dict(existing.get(run_id, make_row()))
        state_path = run_dir / "state.json"
        state = json.loads(state_path.read_text(encoding="utf-8")) if state_path.exists() else {}
        row = dict(old)
        for key in ("samples", "successes", "failures", "error_rate_pct", "throughput_req_s",
                    "mean_ms", "p50_ms", "p95_ms", "p99_ms", "max_ms"):
            row[key] = summary[key]
        http_429, http_5xx = http_errors(summary)
        row["http_429"] = http_429
        row["http_5xx"] = http_5xx
        row["scenario"] = "seckill"
        row["evidence_dir"] = str(run_dir.relative_to(REPO_ROOT)).replace("\\", "/")
        if state:
            accepted = summary["successes"]
            if int(state.get("accepted", -1)) != accepted:
                raise RuntimeError(
                    f"{run_id}: accepted mismatch state.json={state.get('accepted')} "
                    f"vs corrected JTL successes={accepted}"
                )
            row["seckill_voucher_id"] = state.get("voucher_id", "")
            row["initial_stock"] = state.get("initial_stock", "")
            row["accepted"] = accepted
            row["final_redis_stock"] = state.get("state", {}).get("redis_stock", "")
            row["mysql_orders"] = state.get("state", {}).get("mysql_orders", "")
            row["distinct_users"] = state.get("state", {}).get("mysql_distinct_users", "")
            row["duplicate_orders"] = state.get("state", {}).get("mysql_duplicate_users", "")
            row["redis_ordered_users"] = state.get("state", {}).get("redis_ordered_users", "")
            row["stream_pending"] = state.get("state", {}).get("stream_pending", "")
            row["stream_dlq"] = state.get("state", {}).get("stream_dlq", "")
            row["convergence_seconds"] = state.get("convergence_seconds", "")
            row["correctness_pass"] = "true" if state.get("correctness_pass") else "false"
        row["status"] = old.get("status") or "done"
        rebuilt.append(row)

    kept = [r for r in existing.values() if r.get("scenario") not in ("blog-hot", "seckill")]
    rebuilt.sort(key=lambda r: (r.get("scenario", ""), str(r.get("threads", "")), str(r.get("run", ""))))
    write_results_rows(kept + rebuilt)
    print(f"reanalyzed blog-hot+seckill from existing JTLs: {len(rebuilt)} rows "
          f"(no traffic sent); total rows={len(kept) + len(rebuilt)}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Stage6A benchmark orchestrator")
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("gen-env")
    p.add_argument("--force", action="store_true")

    p = sub.add_parser("bootstrap")
    p.add_argument("--users", type=int, default=520)
    p.add_argument("--tokens", type=int, default=520)
    p.add_argument("--skip-tokens", action="store_true")
    p.add_argument("--skip-build", action="store_true")

    p = sub.add_parser("shop")
    p.add_argument("--warmup-seconds", type=int, default=20)
    p.add_argument("--measured-seconds", type=int, default=60)
    p.add_argument("--window-seconds", type=int, default=60)

    p = sub.add_parser("shop-type")
    p.add_argument("--warmup-seconds", type=int, default=20)
    p.add_argument("--measured-seconds", type=int, default=60)

    p = sub.add_parser("blog")
    p.add_argument("--warmup-seconds", type=int, default=20)
    p.add_argument("--measured-seconds", type=int, default=60)

    p = sub.add_parser("seckill")
    p.add_argument("--levels", type=int, nargs="+", default=[100, 300, 500])
    p.add_argument("--seckill-ramp-up", type=int, default=2)

    sub.add_parser("report")
    sub.add_parser("reanalyze")
    return parser


def main() -> int:
    args = build_parser().parse_args()
    try:
        if args.command == "gen-env":
            return cmd_gen_env(args)
        if args.command == "bootstrap":
            return cmd_bootstrap(args)
        if args.command == "shop":
            return cmd_shop(args)
        if args.command == "shop-type":
            return cmd_shop_type(args)
        if args.command == "blog":
            return cmd_blog(args)
        if args.command == "seckill":
            return cmd_seckill(args)
        if args.command == "report":
            return cmd_report(args)
        if args.command == "reanalyze":
            return cmd_reanalyze(args)
        return 2
    except BenchmarkFailure as exc:
        print(f"BENCHMARK FAILURE (evidence kept, PARTIAL): {exc}", file=sys.stderr)
        return 3
    except Exception as exc:
        print(f"ERROR: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
