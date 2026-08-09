"""Stage6A test-data seeding in the isolated local benchmark MySQL.

Creates clearly test-only users (199xxxxxx phone block) in the local
linklife_identity database. Never touches shared/production data and never
uses real phone numbers.
"""

from __future__ import annotations

import argparse
import os
import sys

import pymysql


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Seed Stage6A test users")
    p.add_argument("--count", type=int, default=520)
    p.add_argument("--mysql-host", default=os.getenv("LINKLIFE_MYSQL_HOST", "127.0.0.1"))
    p.add_argument("--mysql-port", type=int, default=int(os.getenv("LINKLIFE_MYSQL_PORT", "13306")))
    p.add_argument("--mysql-user", default=os.getenv("LINKLIFE_MYSQL_USER", "root"))
    p.add_argument("--mysql-password", default=os.getenv("LINKLIFE_MYSQL_PASSWORD", ""))
    p.add_argument("--mysql-database", default=os.getenv("LINKLIFE_MYSQL_DATABASE", "linklife_identity"))
    return p


def main() -> int:
    args = build_parser().parse_args()
    if args.count < 1 or args.count > 10000:
        print("count must be in 1..10000", file=sys.stderr)
        return 2
    conn = pymysql.connect(
        host=args.mysql_host,
        port=args.mysql_port,
        user=args.mysql_user,
        password=args.mysql_password,
        database=args.mysql_database,
        charset="utf8mb4",
        autocommit=True,
    )
    try:
        base = 19900000001
        rows = [(base + i, f"test-user-{base + i}") for i in range(args.count)]
        with conn.cursor() as cursor:
            for phone, nick in rows:
                cursor.execute(
                    "INSERT IGNORE INTO tb_user (phone, nick_name) VALUES (%s, %s)",
                    (phone, nick),
                )
            cursor.execute("SELECT COUNT(*) FROM tb_user")
            total = int(cursor.fetchone()[0])
            cursor.execute("SELECT COUNT(*) FROM tb_user WHERE phone LIKE '199%'")
            test_total = int(cursor.fetchone()[0])
        print(f"seeded/verified: total users={total}, test-block(199xxxx)={test_total}")
        return 0
    finally:
        conn.close()


if __name__ == "__main__":
    raise SystemExit(main())
