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


def env(name: str, default: str = "") -> str:
    value = os.getenv(name)
    return default if value is None else value


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
    redis_db: int
    redis_password: Optional[str]


def settings_from_args(args: argparse.Namespace) -> Settings:
    return Settings(
        base_url=args.base_url.rstrip("/"),
        mysql_host=args.mysql_host,
        mysql_port=args.mysql_port,
        mysql_user=args.mysql_user,
        mysql_password=args.mysql_password,
        mysql_database=args.mysql_database,
        redis_host=args.redis_host,
        redis_port=args.redis_port,
        redis_db=args.redis_db,
        redis_password=args.redis_password.strip() or None,
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Stage6A: read linklife_identity.tb_user users, mint tokens through Gateway "
                    "/user/code -> Redis admin code -> /user/login; writes gitignored tokens.csv."
    )
    parser.add_argument("--limit", type=int, default=100)
    parser.add_argument("--workers", type=int, default=10)
    parser.add_argument("--output", default=str(Path(__file__).resolve().parent / "tokens.csv"))
    parser.add_argument("--base-url", default=env("LINKLIFE_BASE_URL", "http://127.0.0.1:8080/api"))
    parser.add_argument("--mysql-host", default=env("LINKLIFE_MYSQL_HOST", "127.0.0.1"))
    parser.add_argument("--mysql-port", type=int, default=int(env("LINKLIFE_MYSQL_PORT", "13306")))
    parser.add_argument("--mysql-user", default=env("LINKLIFE_MYSQL_USER", "root"))
    parser.add_argument("--mysql-password", default=env("LINKLIFE_MYSQL_PASSWORD", ""))
    parser.add_argument("--mysql-database", default=env("LINKLIFE_MYSQL_DATABASE", "linklife_identity"))
    parser.add_argument("--redis-host", default=env("LINKLIFE_REDIS_HOST", "127.0.0.1"))
    parser.add_argument("--redis-port", type=int, default=int(env("LINKLIFE_REDIS_PORT", "16379")))
    parser.add_argument("--redis-db", type=int, default=int(env("LINKLIFE_REDIS_DB", "0")))
    parser.add_argument("--redis-password", default=env("LINKLIFE_REDIS_PASSWORD", ""))
    parser.add_argument("--request-timeout", type=float, default=10.0)
    parser.add_argument("--code-wait-seconds", type=float, default=3.0)
    return parser


def query_users(settings: Settings, limit: int) -> list[tuple[int, str]]:
    connection = pymysql.connect(
        host=settings.mysql_host,
        port=settings.mysql_port,
        user=settings.mysql_user,
        password=settings.mysql_password,
        database=settings.mysql_database,
        charset="utf8mb4",
        autocommit=True,
    )
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                SELECT id, phone
                FROM tb_user
                WHERE phone IS NOT NULL AND phone <> ''
                ORDER BY id
                LIMIT %s
                """,
                (limit,),
            )
            return [(int(row[0]), str(row[1])) for row in cursor.fetchall()]
    finally:
        connection.close()


def make_redis(settings: Settings) -> redis.Redis:
    return redis.Redis(
        host=settings.redis_host,
        port=settings.redis_port,
        db=settings.redis_db,
        password=settings.redis_password,
        decode_responses=True,
        socket_connect_timeout=5,
        socket_timeout=5,
    )


def parse_json(response: requests.Response) -> dict:
    try:
        data = response.json()
    except ValueError as exc:
        raise RuntimeError(
            f"接口未返回 JSON，HTTP {response.status_code}，响应：{response.text[:200]}"
        ) from exc
    if not isinstance(data, dict):
        raise RuntimeError("接口返回 JSON 不是对象")
    return data


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
        result = parse_json(response)
        if result.get("success") is not True:
            return user_id, phone, None, f"发送验证码失败：{result.get('errorMsg')}"

        code_key = f"identity:login:code:{phone}"
        deadline = time.monotonic() + code_wait_seconds
        code = None
        while time.monotonic() < deadline:
            code = rdb.get(code_key)
            if code:
                break
            time.sleep(0.05)
        if not code:
            return user_id, phone, None, f"Redis 未找到 {code_key}"

        response = session.post(
            f"{settings.base_url}/user/login",
            json={"phone": phone, "code": code},
            timeout=request_timeout,
        )
        response.raise_for_status()
        result = parse_json(response)
        token = result.get("data")
        if result.get("success") is not True or not isinstance(token, str) or not token.strip():
            return user_id, phone, None, f"登录失败：{result.get('errorMsg')}"
        return user_id, phone, token.strip(), None
    except requests.RequestException as exc:
        return user_id, phone, None, f"HTTP 请求异常：{exc}"
    except Exception as exc:
        return user_id, phone, None, f"{type(exc).__name__}：{exc}"
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
    code_wait_seconds: float = 3.0,
) -> tuple[Path, int, list[tuple[int, str, str]]]:
    if limit <= 0:
        raise ValueError("--limit 必须大于 0")
    workers = max(1, min(workers, 20))
    users = query_users(settings, limit)
    if not users:
        raise RuntimeError("tb_user 中未查询到可用手机号")

    output.parent.mkdir(parents=True, exist_ok=True)
    successes: list[tuple[int, str, str]] = []
    failures: list[tuple[int, str, str]] = []
    print(f"查询到 {len(users)} 个已有用户，使用 {workers} 个并行任务准备 token。")

    with ThreadPoolExecutor(max_workers=workers, thread_name_prefix="token-login") as executor:
        futures = {
            executor.submit(
                login_one,
                settings,
                user_id,
                phone,
                request_timeout,
                code_wait_seconds,
            ): (user_id, phone)
            for user_id, phone in users
        }
        completed = 0
        for future in as_completed(futures):
            user_id, phone, token, error = future.result()
            completed += 1
            if token:
                successes.append((user_id, phone, token))
            else:
                failures.append((user_id, phone, error or "未知错误"))
            if completed % 20 == 0 or completed == len(users):
                print(f"进度 {completed}/{len(users)}，成功 {len(successes)}，失败 {len(failures)}。")

    successes.sort(key=lambda item: item[0])
    tokens = [item[2] for item in successes]
    if any(not token for token in tokens):
        raise RuntimeError("结果中存在空 token")
    if len(tokens) != len(set(tokens)):
        raise RuntimeError("结果中存在重复 token，已停止写入")

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
            writer.writerows(failures)
        print(f"失败明细：{failure_file}")
        for _, phone, reason in failures[:5]:
            print(f"  {mask_phone(phone)}：{reason}")

    print(f"token CSV：{output}")
    print(f"成功 {len(successes)}，失败 {len(failures)}。完整 token 未打印。")
    return output, len(successes), failures


def main() -> int:
    args = build_parser().parse_args()
    try:
        generate_tokens(
            settings=settings_from_args(args),
            limit=args.limit,
            workers=args.workers,
            output=Path(args.output).resolve(),
            request_timeout=args.request_timeout,
            code_wait_seconds=args.code_wait_seconds,
        )
        return 0
    except Exception as exc:
        print(f"准备 token 失败：{type(exc).__name__}：{exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
