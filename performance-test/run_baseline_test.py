from __future__ import annotations

import argparse
import csv
import math
import os
import shutil
import statistics
import subprocess
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path

import pymysql
import redis
import requests

from prepare_tokens import Settings, env, generate_tokens

SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_JMX = SCRIPT_DIR / "jmeter" / "baseline-seckill-test.jmx"
DEFAULT_TOKENS = SCRIPT_DIR / "tokens.csv"
DEFAULT_OUTPUT = SCRIPT_DIR / "output"


@dataclass
class Verification:
    redis_stock: int
    redis_order_users: int
    mysql_orders: int
    mysql_distinct_users: int
    mysql_duplicate_users: int
    stream_messages: int


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="自动准备用户 token、创建新秒杀券、执行 JMeter 并核对结果。")
    p.add_argument("--threads", type=int, default=100)
    p.add_argument("--stock", type=int, default=100)
    p.add_argument("--ramp-up", type=int, default=5)
    p.add_argument("--user-limit", type=int, default=100)
    p.add_argument("--workers", type=int, default=10)
    p.add_argument("--shop-id", type=int, default=1)
    p.add_argument("--tokens-csv", default=str(DEFAULT_TOKENS))
    p.add_argument("--skip-token-prepare", action="store_true")
    p.add_argument("--jmx", default=str(DEFAULT_JMX))
    p.add_argument("--output-root", default=str(DEFAULT_OUTPUT))
    p.add_argument("--append-doc", default="")
    p.add_argument("--base-url", default=env("LINKLIFE_BASE_URL", "http://127.0.0.1:8080/api"))
    p.add_argument("--mysql-host", default=env("LINKLIFE_MYSQL_HOST", "127.0.0.1"))
    p.add_argument("--mysql-port", type=int, default=int(env("LINKLIFE_MYSQL_PORT", "13306")))
    p.add_argument("--mysql-user", default=env("LINKLIFE_MYSQL_USER", "root"))
    p.add_argument("--mysql-password", default=env("LINKLIFE_MYSQL_PASSWORD", ""))
    p.add_argument("--mysql-database", default=env("LINKLIFE_MYSQL_DATABASE", "linklife_identity"))
    p.add_argument("--transaction-database", default=env("LINKLIFE_TRANSACTION_DATABASE", "linklife_transaction"))
    p.add_argument("--redis-host", default=env("LINKLIFE_REDIS_HOST", "127.0.0.1"))
    p.add_argument("--redis-port", type=int, default=int(env("LINKLIFE_REDIS_PORT", "16379")))
    p.add_argument("--redis-db", type=int, default=int(env("LINKLIFE_REDIS_DB", "0")))
    p.add_argument("--redis-password", default=env("LINKLIFE_REDIS_PASSWORD", ""))
    p.add_argument("--jmeter-home", default=env("LINKLIFE_JMETER_HOME", ""))
    p.add_argument("--jmeter-bin", default=env("LINKLIFE_JMETER_BIN", ""))
    p.add_argument("--admin-token", default=env("LINKLIFE_ADMIN_TOKEN", ""))
    p.add_argument("--wait-seconds", type=int, default=30)
    return p


def db(settings: Settings):
    return pymysql.connect(
        host=settings.mysql_host,
        port=settings.mysql_port,
        user=settings.mysql_user,
        password=settings.mysql_password,
        database=settings.mysql_database,
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
        autocommit=True,
    )


def rdb(settings: Settings) -> redis.Redis:
    return redis.Redis(
        host=settings.redis_host,
        port=settings.redis_port,
        db=settings.redis_db,
        password=settings.redis_password,
        decode_responses=True,
        socket_connect_timeout=5,
        socket_timeout=5,
    )


def create_voucher(base_url: str, shop_id: int, stock: int, token: str) -> tuple[int, str]:
    now = datetime.now().replace(microsecond=0)
    title = f"baseline-seckill-{now:%Y%m%d%H%M%S}"
    body = {
        "shopId": shop_id,
        "title": title,
        "subTitle": "自动化基线压测券",
        "rules": "仅用于本地自动化基线压测",
        "payValue": 100,
        "actualValue": 1000,
        "type": 1,
        "status": 1,
        "stock": stock,
        "beginTime": (now - timedelta(minutes=1)).isoformat(),
        "endTime": (now + timedelta(hours=1)).isoformat(),
    }
    headers = {"Content-Type": "application/json"}
    if token.strip():
        headers["Authorization"] = token.strip()
    response = requests.post(
        f"{base_url.rstrip('/')}/voucher/seckill",
        headers=headers,
        json=body,
        timeout=15,
    )
    response.raise_for_status()
    result = response.json()
    if result.get("success") is not True:
        raise RuntimeError(f"添加秒杀券失败：{result.get('errorMsg')}")
    try:
        return int(result.get("data")), title
    except (TypeError, ValueError) as exc:
        raise RuntimeError(f"接口未返回合法 voucherId：{result}") from exc


def validate_voucher(settings: Settings, voucher_id: int, stock: int) -> None:
    with db(settings) as conn, conn.cursor() as cursor:
        cursor.execute("SELECT id FROM tb_voucher WHERE id=%s", (voucher_id,))
        voucher = cursor.fetchone()
        cursor.execute("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=%s", (voucher_id,))
        seckill = cursor.fetchone()
        cursor.execute("SELECT COUNT(*) AS c FROM tb_voucher_order WHERE voucher_id=%s", (voucher_id,))
        orders = int(cursor.fetchone()["c"])
    client = rdb(settings)
    try:
        redis_stock = client.get(f"transaction:seckill:stock:{voucher_id}")
        ordered_users = int(client.scard(f"transaction:seckill:order:{voucher_id}"))
    finally:
        client.close()
    if not voucher or not seckill:
        raise RuntimeError("新秒杀券未完整写入 MySQL")
    if int(seckill["stock"]) != stock:
        raise RuntimeError(f"MySQL 库存不一致：{seckill['stock']} != {stock}")
    if redis_stock is None or int(redis_stock) != stock:
        raise RuntimeError(f"Redis 库存不一致：{redis_stock} != {stock}")
    if orders != 0 or ordered_users != 0:
        raise RuntimeError("新券在压测前已有订单或购买记录")


def resolve_jmeter(args: argparse.Namespace) -> Path:
    candidates: list[Path] = []
    if args.jmeter_bin:
        candidates.append(Path(args.jmeter_bin))
    if args.jmeter_home:
        home = Path(args.jmeter_home)
        candidates += [home / "bin" / "jmeter.bat", home / "bin" / "jmeter"]
    found = shutil.which("jmeter.bat") or shutil.which("jmeter")
    if found:
        candidates.append(Path(found))
    for item in candidates:
        if item.exists():
            return item.resolve()
    raise RuntimeError("未找到 JMeter，请设置 LINKLIFE_JMETER_HOME 或 --jmeter-bin")


def run_jmeter(jmeter: Path, jmx: Path, csv_file: Path, voucher_id: int, threads: int,
               ramp_up: int, jtl: Path, html: Path) -> None:
    if html.exists():
        shutil.rmtree(html)
    jtl.parent.mkdir(parents=True, exist_ok=True)
    args = [
        "-n", "-t", str(jmx),
        f"-JvoucherId={voucher_id}",
        f"-Jthreads={threads}",
        f"-JrampUp={ramp_up}",
        f"-JcsvFile={csv_file}",
        "-l", str(jtl), "-e", "-o", str(html),
    ]
    command = ["cmd.exe", "/c", str(jmeter), *args] if jmeter.suffix.lower() in {".bat", ".cmd"} else [str(jmeter), *args]
    result = subprocess.run(command, check=False)
    if result.returncode != 0:
        raise RuntimeError(f"JMeter 执行失败，退出码 {result.returncode}")


def stream_count(client: redis.Redis, voucher_id: int) -> int:
    count = 0
    for _, fields in client.xrange("transaction:stream.orders", min="-", max="+"):
        if str(fields.get("voucherId")) == str(voucher_id):
            count += 1
    return count


def verification(settings: Settings, voucher_id: int) -> Verification:
    with db(settings) as conn, conn.cursor() as cursor:
        cursor.execute(
            "SELECT COUNT(*) AS orders, COUNT(DISTINCT user_id) AS users FROM tb_voucher_order WHERE voucher_id=%s",
            (voucher_id,),
        )
        row = cursor.fetchone()
        cursor.execute(
            """
            SELECT COUNT(*) AS c FROM (
              SELECT user_id FROM tb_voucher_order WHERE voucher_id=%s
              GROUP BY user_id HAVING COUNT(*) > 1
            ) t
            """,
            (voucher_id,),
        )
        duplicates = int(cursor.fetchone()["c"])
    client = rdb(settings)
    try:
        raw_stock = client.get(f"transaction:seckill:stock:{voucher_id}")
        return Verification(
            redis_stock=int(raw_stock) if raw_stock is not None else -1,
            redis_order_users=int(client.scard(f"transaction:seckill:order:{voucher_id}")),
            mysql_orders=int(row["orders"]),
            mysql_distinct_users=int(row["users"]),
            mysql_duplicate_users=duplicates,
            stream_messages=stream_count(client, voucher_id),
        )
    finally:
        client.close()


def wait_orders(settings: Settings, voucher_id: int, expected: int, seconds: int) -> Verification:
    end = time.monotonic() + seconds
    current = verification(settings, voucher_id)
    while time.monotonic() < end and current.mysql_orders < expected:
        time.sleep(0.25)
        current = verification(settings, voucher_id)
    return current


def pct(values: list[int], p: float) -> float:
    if not values:
        return 0.0
    values = sorted(values)
    return float(values[max(0, min(len(values) - 1, math.ceil(len(values) * p) - 1))])


def parse_jtl(path: Path) -> dict:
    elapsed: list[int] = []
    stamps: list[int] = []
    ok = 0
    failed = 0
    with path.open("r", encoding="utf-8-sig", newline="") as fp:
        for row in csv.DictReader(fp):
            try:
                elapsed.append(int(row.get("elapsed", "0")))
                stamps.append(int(row.get("timeStamp", "0")))
            except ValueError:
                pass
            if row.get("success", "").lower() == "true":
                ok += 1
            else:
                failed += 1
    duration = max(0.001, (max(stamps) - min(stamps)) / 1000) if len(stamps) > 1 else 0.001
    return {
        "samples": ok + failed,
        "successes": ok,
        "failures": failed,
        "avg": round(statistics.mean(elapsed), 2) if elapsed else 0,
        "min": min(elapsed) if elapsed else 0,
        "max": max(elapsed) if elapsed else 0,
        "p50": pct(elapsed, 0.50),
        "p90": pct(elapsed, 0.90),
        "p95": pct(elapsed, 0.95),
        "p99": pct(elapsed, 0.99),
        "throughput": round((ok + failed) / duration, 2),
    }



def read_jmeter_throughput(html_report: Path) -> float:
    statistics_file = html_report / "statistics.json"
    if not statistics_file.exists():
        raise RuntimeError(f"未找到 JMeter 统计文件：{statistics_file}")
    data = json.loads(statistics_file.read_text(encoding="utf-8"))
    total = data.get("Total")
    if not isinstance(total, dict) or "throughput" not in total:
        raise RuntimeError("statistics.json 中未找到 Total.throughput")
    return round(float(total["throughput"]), 2)

def markdown(args, voucher_id, title, metrics, check, expected, passed) -> str:
    return f"""# LinkLife 原版秒杀性能基线

- 执行时间：{datetime.now().isoformat(timespec='seconds')}
- 测试券：{title}
- voucherId：{voucher_id}
- 线程数：{args.threads}
- 初始库存：{args.stock}
- Ramp-Up：{args.ramp_up} 秒
- 预期成功订单：{expected}

## JMeter 指标

| 指标 | 结果 |
|---|---:|
| 样本数 | {metrics['samples']} |
| 业务成功样本 | {metrics['successes']} |
| 失败样本 | {metrics['failures']} |
| 平均响应时间 | {metrics['avg']} ms |
| 最小响应时间 | {metrics['min']} ms |
| 最大响应时间 | {metrics['max']} ms |
| P50 | {metrics['p50']} ms |
| P90 | {metrics['p90']} ms |
| P95 | {metrics['p95']} ms |
| P99 | {metrics['p99']} ms |
| JMeter 吞吐量 | {metrics['throughput']} 请求/秒 |

## 一致性核对

| 检查项 | 结果 |
|---|---:|
| Redis 剩余库存 | {check.redis_stock} |
| Redis 已购用户数 | {check.redis_order_users} |
| MySQL 订单数 | {check.mysql_orders} |
| MySQL 不同用户数 | {check.mysql_distinct_users} |
| MySQL 重复下单用户数 | {check.mysql_duplicate_users} |
| 当前测试券 Stream 消息数 | {check.stream_messages} |

## 结论

**{'通过' if passed else '未通过'}**
"""


def main() -> int:
    args = parser().parse_args()
    if args.threads <= 0 or args.stock <= 0 or args.user_limit < args.threads:
        print("参数错误：threads、stock 必须大于0，user-limit 不能小于 threads。", file=sys.stderr)
        return 2
    tx_settings = Settings(
        base_url=args.base_url.rstrip("/"),
        mysql_host=args.mysql_host,
        mysql_port=args.mysql_port,
        mysql_user=args.mysql_user,
        mysql_password=args.mysql_password,
        mysql_database=args.transaction_database,
        redis_host=args.redis_host,
        redis_port=args.redis_port,
        redis_db=args.redis_db,
        redis_password=args.redis_password.strip() or None,
    )
    settings = Settings(
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
    tokens = Path(args.tokens_csv).resolve()
    try:
        if args.skip_token_prepare:
            with tokens.open("r", encoding="utf-8-sig") as fp:
                token_count = max(0, sum(1 for _ in fp) - 1)
        else:
            _, token_count, _ = generate_tokens(settings, args.user_limit, args.workers, tokens)
        if token_count < args.threads:
            raise RuntimeError(f"可用 token {token_count} 个，少于线程数 {args.threads}")

        jmeter_bin = resolve_jmeter(args)

        voucher_id, title = create_voucher(settings.base_url, args.shop_id, args.stock, args.admin_token)
        print(f"新秒杀券创建成功：voucherId={voucher_id}")
        validate_voucher(tx_settings, voucher_id, args.stock)
        print("MySQL 与 Redis 初始化检查通过。")

        stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        run_dir = Path(args.output_root).resolve() / f"run-{stamp}-voucher-{voucher_id}"
        jtl = run_dir / "result.jtl"
        html = run_dir / "html-report"
        report = run_dir / "baseline-report.md"
        run_jmeter(jmeter_bin, Path(args.jmx).resolve(), tokens, voucher_id,
                   args.threads, args.ramp_up, jtl, html)

        expected = min(args.stock, args.threads)
        check = wait_orders(tx_settings, voucher_id, expected, args.wait_seconds)
        metrics = parse_jtl(jtl)
        metrics["throughput"] = read_jmeter_throughput(html)
        passed = (
            metrics["samples"] == args.threads
            and metrics["successes"] == expected
            and metrics["failures"] == args.threads - expected
            and check.redis_stock == args.stock - expected
            and check.redis_order_users == expected
            and check.mysql_orders == expected
            and check.mysql_distinct_users == expected
            and check.mysql_duplicate_users == 0
            and check.stream_messages >= expected
        )
        text = markdown(args, voucher_id, title, metrics, check, expected, passed)
        report.write_text(text, encoding="utf-8")
        if args.append_doc:
            doc = Path(args.append_doc).resolve()
            doc.parent.mkdir(parents=True, exist_ok=True)
            with doc.open("a", encoding="utf-8") as fp:
                fp.write("\n\n---\n\n" + text)
        print(text)
        print(f"JTL：{jtl}")
        print(f"HTML 报告：{html / 'index.html'}")
        print(f"Markdown 报告：{report}")
        return 0 if passed else 1
    except Exception as exc:
        print(f"基线压测失败：{type(exc).__name__}：{exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
