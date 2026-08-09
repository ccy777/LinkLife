"""Shared helpers for Stage6B fault drills (isolated local fault-drill env)."""

from __future__ import annotations

import base64
import csv
import datetime as _dt
import json
import os
import re
import secrets
import subprocess
import time
from pathlib import Path
from typing import Optional

import pymysql
import redis
import requests

SCRIPT_DIR = Path(__file__).resolve().parent
PERF_DIR = SCRIPT_DIR.parent
REPO_ROOT = PERF_DIR.parent
COMPOSE_FILE = PERF_DIR / "deploy" / "docker-compose.stage6b.yml"
ENV_FILE = PERF_DIR / "stage6b.env"
EVIDENCE_ROOT = REPO_ROOT / ".linklife-local" / "evidence" / "stage6b"
TOKENS_CSV = PERF_DIR / "tokens.csv"

GATEWAY = "http://127.0.0.1:8080"
API = GATEWAY + "/api"
NACOS = "http://127.0.0.1:18848"
MYSQL_PORT = 13306
REDIS_PORT = 16379

ADMIN_PHONE = "13686869696"

SERVICE_NAMES = [
    "linklife-identity-service",
    "linklife-merchant-service",
    "linklife-transaction-service",
    "linklife-social-service",
]

HTTP_TIMEOUT = 10


def now_iso() -> str:
    return _dt.datetime.now().isoformat(timespec="seconds")


def ts() -> float:
    return time.monotonic()


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


def gen_env(force: bool = False) -> None:
    if ENV_FILE.exists() and not force:
        print(f"env file already exists: {ENV_FILE}")
        return

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
        "NACOS_AUTH_TOKEN": base64.b64encode(secrets.token_bytes(32)).decode(),
        "NACOS_AUTH_IDENTITY_KEY": secret(8),
        "NACOS_AUTH_IDENTITY_VALUE": secret(8),
        "LINKLIFE_ADMIN_USER_IDS": "1",
        # Drill A: Gateway Sentinel ENABLED with local test-only low thresholds.
        "LINKLIFE_SENTINEL_GATEWAY_ENABLED": "true",
        "LINKLIFE_SENTINEL_HOT_BLOG_QPS": "2",
        "LINKLIFE_SENTINEL_SHOP_OF_TYPE_QPS": "2",
        "LINKLIFE_SENTINEL_SECKILL_QPS": "2",
        # Drill B: Social -> Identity breaker (defaults).
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
    lines = ["# Stage6B private env (auto-generated, gitignored; do not print)", ""]
    lines += [f"{k}={v}" for k, v in values.items()]
    ENV_FILE.write_text("\n".join(lines) + "\n", encoding="utf-8")
    os.chmod(ENV_FILE, 0o600)
    print(f"generated {ENV_FILE}")


def compose(args: list[str], timeout: int = 600) -> subprocess.CompletedProcess:
    cmd = ["docker", "compose", "--env-file", str(ENV_FILE), "-f", str(COMPOSE_FILE), *args]
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, cwd=str(REPO_ROOT))


def compose_ok(args: list[str], timeout: int = 600) -> str:
    result = compose(args, timeout=timeout)
    if result.returncode != 0:
        raise RuntimeError(f"compose failed ({result.returncode}): {' '.join(args)}\n{result.stdout}\n{result.stderr}")
    return result.stdout


def wait_http(url: str, timeout: int, ok_status: tuple = (200,)) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            resp = requests.get(url, timeout=5)
            if resp.status_code in ok_status:
                return True
        except requests.RequestException:
            pass
        time.sleep(2)
    return False


def nacos_service_ready(service: str, timeout: int = 180) -> bool:
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
            resp = requests.get(f"{API}/shop/1", timeout=5)
            if resp.status_code == 200 and resp.json().get("success") is True:
                return True
        except (requests.RequestException, ValueError):
            pass
        time.sleep(2)
    return False


def redis_admin(env: dict) -> redis.Redis:
    return redis.Redis(
        host="127.0.0.1", port=REDIS_PORT, db=0,
        password=env["REDIS_ADMIN_PASSWORD"], decode_responses=True,
        socket_connect_timeout=5, socket_timeout=5,
    )


def redis_user(env: dict, username: str) -> redis.Redis:
    return redis.Redis(
        host="127.0.0.1", port=REDIS_PORT, db=0,
        username=username, password=env[f"REDIS_{username.upper()}_PASSWORD"],
        decode_responses=True, socket_connect_timeout=5, socket_timeout=5,
    )


def mysql_conn(env: dict, database: str):
    return pymysql.connect(
        host="127.0.0.1", port=MYSQL_PORT, user="root",
        password=env["MYSQL_ROOT_PASSWORD"], database=database,
        charset="utf8mb4", cursorclass=pymysql.cursors.DictCursor, autocommit=True,
    )


def http_json(method: str, path: str, token: Optional[str] = None, body=None, timeout: int = HTTP_TIMEOUT) -> dict:
    headers = {}
    if token:
        headers["Authorization"] = token
    try:
        resp = requests.request(method, f"{API}{path}", headers=headers, json=body, timeout=timeout)
    except requests.RequestException as exc:
        # Record the timeout/connect error instead of aborting the drill; callers
        # may retry once to drain a stale pooled connection after a fault.
        return {
            "status": 0,
            "body": {"success": False, "errorMsg": type(exc).__name__ + ": " + str(exc)[:120]},
            "elapsed_ms": round(timeout * 1000),
            "transport_error": True,
        }
    try:
        payload = resp.json()
    except ValueError:
        payload = {"_raw": resp.text[:200]}
    return {
        "status": resp.status_code,
        "body": payload,
        "elapsed_ms": round(resp.elapsed.total_seconds() * 1000, 1),
    }


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


def token_for_phone(phone: str) -> str:
    for p, token in read_tokens(None):
        if p == phone:
            return token
    raise RuntimeError(f"token not found for phone {phone}")


def write_evidence(drill: str, name: str, data) -> Path:
    path = EVIDENCE_ROOT / drill / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    return path


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def seed_extra(env: dict) -> None:
    """Insert Drill B/C task-only data: identity users 3/6, follow rows, blog likes."""
    with mysql_conn(env, "linklife_identity") as conn, conn.cursor() as cur:
        cur.execute(
            "INSERT IGNORE INTO tb_user (id, phone, nick_name) VALUES (%s,%s,%s), (%s,%s,%s)",
            (3, "19800000003", "test-user-3", 6, "19800000006", "test-user-6"),
        )
    with mysql_conn(env, "linklife_social") as conn, conn.cursor() as cur:
        cur.execute(
            "INSERT IGNORE INTO tb_follow (user_id, follow_user_id) VALUES (1,2),(1,3),(4,3)"
        )
        cur.execute(
            "INSERT IGNORE INTO tb_blog_like (blog_id, user_id) VALUES "
            "(4,2),(4,3),(4,4),(4,5),(4,6)"
        )
    print("seeded drill users/follows/likes")


def utc_naive(delta_minutes: int) -> str:
    now = _dt.datetime.now(_dt.timezone.utc).replace(microsecond=0)
    return (now + _dt.timedelta(minutes=delta_minutes)).strftime("%Y-%m-%dT%H:%M:%S")
