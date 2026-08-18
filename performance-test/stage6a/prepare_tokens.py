"""Stage6A token preparation against the final microservice architecture.

Flow (must go through the Gateway, 4-DB ownership, Redis ACL):
    POST /api/user/code?phone=...
    -> read verification code from task Redis admin key identity:login:code:{phone}
    -> POST /api/user/login {phone, code} -> token

Full tokens / verification codes / complete phone numbers are never printed
and never written to evidence; only the gitignored tokens CSV is produced.
"""

from __future__ import annotations

import argparse
import csv
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

import pymysql
import redis
import requests


def mask_phone(phone: str) -> str:
    return f"{phone[:3]}****{phone[-4:]}" if len(phone) >= 7 else "***"


@dataclass(frozen=True)
class Settings:
    base_url: str
    mysql_host: str
    mysql_port: int
    mysql_user: str
    mysql_password: str
    mysql_database: str
    redis_host: str
    redis_port: int
    redis_password: Optional[str]


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Stage6A login-token preparation through the Gateway")
    parser.add_argument("--limit", type=int, default=100)
    parser.add_argument("--workers", type=int, default=10)
    parser.add_argument("--output", default=str(Path(__file__).resolve().parents[1] / "tokens.csv"))
    parser.add_argument("--base-url", default=os.getenv("LINKLIFE_BASE_URL", "http://127.0.0.1:8080/api"))
    parser.add_argument("--mysql-host", default=os.getenv("LINKLIFE_MYSQL_HOST", "127.0.0.1"))
    parser.add_argument("--mysql-port", type=int, default=int(os.getenv("LINKLIFE_MYSQL_PORT", "13306")))
    parser.add_argument("--mysql-user", default=os.getenv("LINKLIFE_MYSQL_USER", "root"))
    parser.add_argument("--mysql-password", default=os.getenv("LINKLIFE_MYSQL_PASSWORD", ""))
    parser.add_argument("--mysql-database", default=os.getenv("LINKLIFE_MYSQL_DATABASE", "linklife_identity"))
    parser.add_argument("--redis-host", default=os.getenv("LINKLIFE_REDIS_HOST", "127.0.0.1"))
    parser.add_argument("--redis-port", type=int, default=int(os.getenv("LINKLIFE_REDIS_PORT", "16379")))
    parser.add_argument("--redis-password", default=os.getenv("LINKLIFE_REDIS_PASSWORD", ""))
    parser.add_argument("--request-timeout", type=float, default=10.0)
    parser.add_argument("--code-wait-seconds", type=float, default=5.0)
    return parser


def query_users(settings: Settings, limit: int) -> list[tuple[int, str]]:
    conn = pymysql.connect(
        host=settings.mysql_host,
        port=settings.mysql_port,
        user=settings.mysql_user,
        password=settings.mysql_password,
        database=settings.mysql_database,
        charset="utf8mb4",
        autocommit=True,
    )
    try:
        with conn.cursor() as cursor:
            cursor.execute(
                "SELECT id, phone FROM tb_user WHERE phone IS NOT NULL AND phone <> '' "
                "ORDER BY id LIMIT %s",
                (limit,),
            )
            return [(int(row[0]), str(row[1])) for row in cursor.fetchall()]
    finally:
        conn.close()


def make_redis(settings: Settings) -> redis.Redis:
    return redis.Redis(
        host=settings.redis_host,
        port=settings.redis_port,
        db=0,
        password=settings.redis_password,
        decode_responses=True,
        socket_connect_timeout=5,
        socket_timeout=5,
    )


def login_one(
    settings: Settings,
    user_id: int,
    phone: str,
    request_timeout: float,
    code_wait_seconds: float,
) -> tuple[int, str, Optional[str], Optional[str]]:
    session = requests.Session()
    rdb = make_redis(settings)
    try:
        response = session.post(
            f"{settings.base_url}/user/code",
            params={"phone": phone},
            timeout=request_timeout,
        )
        response.raise_for_status()
        result = response.json()
        if result.get("success") is not True:
            return user_id, phone, None, f"send-code rejected: {result.get('errorMsg')}"

        code_key = f"identity:login:code:{phone}"
        deadline = time.monotonic() + code_wait_seconds
        code: Optional[str] = None
        while time.monotonic() < deadline:
            code = rdb.get(code_key)
            if code:
                break
            time.sleep(0.05)
        if not code:
            return user_id, phone, None, f"code key not found in Redis: {code_key}"

        response = session.post(
            f"{settings.base_url}/user/login",
            json={"phone": phone, "code": code},
            timeout=request_timeout,
        )
        response.raise_for_status()
        result = response.json()
        token = result.get("data")
        if result.get("success") is not True or not isinstance(token, str) or not token.strip():
            return user_id, phone, None, f"login rejected: {result.get('errorMsg')}"
        return user_id, phone, token.strip(), None
    except requests.RequestException as exc:
        return user_id, phone, None, f"HTTP error: {type(exc).__name__}: {exc}"
    except Exception as exc:
        return user_id, phone, None, f"{type(exc).__name__}: {exc}"
    finally:
        session.close()
        try:
            rdb.close()
        except Exception:
            pass


def generate_tokens(
    settings: Settings,
    limit: int,
    workers: int,
    output: Path,
    request_timeout: float = 10.0,
    code_wait_seconds: float = 5.0,
) -> tuple[Path, int, list[tuple[int, str, str]]]:
    if limit <= 0:
        raise ValueError("--limit must be > 0")
    workers = max(1, min(workers, 20))
    users = query_users(settings, limit)
    if not users:
        raise RuntimeError("no users found in linklife_identity.tb_user")

    output.parent.mkdir(parents=True, exist_ok=True)
    successes: list[tuple[int, str, str]] = []
    failures: list[tuple[int, str, str]] = []
    print(f"preparing tokens for {len(users)} users with {workers} workers")

    with ThreadPoolExecutor(max_workers=workers, thread_name_prefix="token-login") as executor:
        futures = {
            executor.submit(login_one, settings, user_id, phone, request_timeout, code_wait_seconds): (user_id, phone)
            for user_id, phone in users
        }
        completed = 0
        for future in as_completed(futures):
            user_id, phone, token, error = future.result()
            completed += 1
            if token:
                successes.append((user_id, phone, token))
            else:
                failures.append((user_id, phone, error or "unknown"))
            if completed % 50 == 0 or completed == len(users):
                print(f"progress {completed}/{len(users)} success={len(successes)} failed={len(failures)}")

    successes.sort(key=lambda item: item[0])
    tokens = [item[2] for item in successes]
    if any(not t for t in tokens):
        raise RuntimeError("empty token in results")
    if len(tokens) != len(set(tokens)):
        raise RuntimeError("duplicate tokens produced; aborting")

    with output.open("w", newline="", encoding="utf-8") as fp:
        writer = csv.writer(fp)
        writer.writerow(["phone", "token"])
        for _, phone, token in successes:
            writer.writerow([phone, token])

    failure_file = output.with_name(f"{output.stem}-failures.csv")
    if failures:
        with failure_file.open("w", newline="", encoding="utf-8") as fp:
            writer = csv.writer(fp)
            writer.writerow(["user_id", "phone", "reason"])
            writer.writerows([(user_id, mask_phone(phone), reason) for user_id, phone, reason in failures])
        print(f"failure details: {failure_file}")
        for _, phone, reason in failures[:5]:
            print(f"  {mask_phone(phone)}: {reason}")

    print(f"tokens csv: {output}")
    print(f"success={len(successes)} failed={len(failures)}; full tokens/codes/phones not printed")
    return output, len(successes), failures


def main() -> int:
    args = build_parser().parse_args()
    settings = Settings(
        base_url=args.base_url.rstrip("/"),
        mysql_host=args.mysql_host,
        mysql_port=args.mysql_port,
        mysql_user=args.mysql_user,
        mysql_password=args.mysql_password,
        mysql_database=args.mysql_database,
        redis_host=args.redis_host,
        redis_port=args.redis_port,
        redis_password=args.redis_password.strip() or None,
    )
    try:
        generate_tokens(
            settings,
            args.limit,
            args.workers,
            Path(args.output).resolve(),
            request_timeout=args.request_timeout,
            code_wait_seconds=args.code_wait_seconds,
        )
        return 0
    except Exception as exc:
        print(f"token preparation failed: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
