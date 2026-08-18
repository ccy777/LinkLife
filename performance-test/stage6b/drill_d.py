"""Drill D: MySQL-down during accepted seckill -> Pending retained -> recovery persist.

Verifies: Redis Lua admission succeeds while MySQL is down; the stream message is
NOT wrongly ACKed/compensated (stock stays 0, qualification kept, submission not
FAILED); after MySQL recovery the same order persists with correct identity,
submission becomes PERSISTED, pending reaches 0, retry marker cleared, DLQ unchanged.
"""

from __future__ import annotations

import time

from common import (
    now_iso,
    http_json,
    mysql_conn,
    redis_admin,
    token_for_phone,
    utc_naive,
    wait_gateway_ready,
    write_evidence,
    ADMIN_PHONE,
    compose,
    compose_ok,
)


def create_voucher(env: dict, admin_token: str) -> int:
    body = {
        "shopId": 1,
        "title": "drill-d-" + str(int(time.time())),
        "subTitle": "stage6b drill-d",
        "rules": "test-only",
        "payValue": 100,
        "actualValue": 1000,
        "type": 1,
        "status": 1,
        "stock": 1,
        "beginTime": utc_naive(-1),
        "endTime": utc_naive(120),
    }
    result = http_json("POST", "/voucher/seckill", token=admin_token, body=body)
    if result["status"] != 200 or result["body"].get("success") is not True:
        raise RuntimeError("voucher create failed: " + str(result))
    return int(result["body"]["data"])


def wait_initialized(env: dict, voucher_id: int, timeout: int = 60) -> bool:
    client = redis_admin(env)
    try:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if client.get("transaction:seckill:stock:" + str(voucher_id)) == "1":
                return True
            time.sleep(1)
    finally:
        client.close()
    return False


def mysql_running() -> bool:
    r = compose(["ps", "--format", "{{.Name}}|{{.Status}}"])
    line = next((l for l in r.stdout.splitlines() if l.startswith("linklife-stage6b-mysql|")), "")
    return "Up" in line and "Exited" not in line


def wait_mysql_ready(env: dict, timeout: int = 90) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        r = compose(["ps", "--format", "{{.Name}}|{{.Status}}"])
        line = next((l for l in r.stdout.splitlines() if l.startswith("linklife-stage6b-mysql|")), "")
        healthy = "healthy" in line
        try:
            conn = mysql_conn(env, "linklife_identity")
            conn.close()
            if healthy:
                return
        except Exception:
            pass
        time.sleep(2)
    raise RuntimeError("mysql not ready within " + str(timeout) + "s")


def user_id_for_phone(env: dict, phone: str) -> int:
    with mysql_conn(env, "linklife_identity") as conn, conn.cursor() as cur:
        cur.execute("SELECT id FROM tb_user WHERE phone=%s", (phone,))
        row = cur.fetchone()
    if row is None:
        raise RuntimeError("user not found for phone " + phone)
    return int(row["id"])


def run(env: dict) -> dict:
    if not wait_gateway_ready():
        raise RuntimeError("gateway not ready")
    admin_token = token_for_phone(ADMIN_PHONE)
    user_token = token_for_phone("19900000001")

    # precondition: MySQL must be running before the drill starts the fault
    if not mysql_running():
        compose_ok(["start", "mysql"])
        wait_mysql_ready(env)
    else:
        wait_mysql_ready(env)
    user_id = user_id_for_phone(env, "19900000001")

    # clean Drill C's leftover test-only pending messages: XACK exact ID, then XDEL,
    # then delete the drillc consumer; afterwards the group PEL must be clean.
    client = redis_admin(env)
    try:
        for mid, fields in client.xrange("transaction:stream.orders", min="-", max="+"):
            if str(fields.get("voucherId")) == "999999999" or str(fields.get("id")) == "887766554433221100":
                client.xack("transaction:stream.orders", "g1", mid)
                client.xdel("transaction:stream.orders", mid)
        try:
            client.xgroup_delconsumer("transaction:stream.orders", "g1", "drillc")
        except Exception:
            pass
        p = client.xpending("transaction:stream.orders", "g1")
        pending_baseline = int(p["pending"]) if p else 0
    finally:
        client.close()
    if pending_baseline != 0:
        raise RuntimeError(
            "Drill D precondition FAILED: group PEL baseline pending=" + str(pending_baseline)
            + " (must be 0 before the fault)"
        )
    evidence = {"drill": "D", "started_at": now_iso(), "pending_baseline": pending_baseline}

    voucher_id = create_voucher(env, admin_token)
    if not wait_initialized(env, voucher_id):
        raise RuntimeError("voucher not initialized")

    with mysql_conn(env, "linklife_transaction") as conn, conn.cursor() as cur:
        cur.execute("SELECT COUNT(*) AS c FROM tb_voucher_order WHERE voucher_id=%s", (voucher_id,))
        orders_before = int(cur.fetchone()["c"])

    client = redis_admin(env)
    try:
        dlq_before = client.xlen("transaction:stream.orders.dlq")
        stream_before = client.xlen("transaction:stream.orders")
    finally:
        client.close()

    evidence["voucher_id"] = voucher_id
    evidence["user_id"] = user_id
    evidence["orders_before"] = orders_before
    evidence["dlq_before"] = dlq_before
    evidence["stream_before"] = stream_before

    # --- fault: stop MySQL only ---
    mysql_down_at = now_iso()
    evidence["mysql_down_at"] = mysql_down_at
    compose_ok(["stop", "mysql"])
    time.sleep(3)
    if mysql_running():
        raise RuntimeError("mysql did not stop")

    # --- admission while DB down ---
    t0 = time.monotonic()
    admit = http_json("POST", "/voucher-order/seckill/" + str(voucher_id), token=user_token)
    admission_ms = round((time.monotonic() - t0) * 1000, 1)
    evidence["admission"] = {"elapsed_ms": admission_ms, "response": admit}
    if admit["status"] != 200 or admit["body"].get("success") is not True:
        raise RuntimeError("admission must succeed while MySQL down: " + str(admit))
    order_id = admit["body"]["data"]

    client = redis_admin(env)
    try:
        stock_after = client.get("transaction:seckill:stock:" + str(voucher_id))
        ordered = client.scard("transaction:seckill:order:" + str(voucher_id))
        submission = client.hgetall("transaction:order:submission:" + str(order_id))
        stream_after = client.xlen("transaction:stream.orders")
    finally:
        client.close()
    evidence["immediate_after_admission"] = {
        "stock": stock_after,
        "ordered_size": ordered,
        "submission": submission,
        "stream_after": stream_after,
    }
    if stock_after != "0" or ordered != 1 or submission.get("state") not in ("ACCEPTED", "PROCESSING"):
        raise RuntimeError("post-admission Redis state wrong: " + str(evidence["immediate_after_admission"]))
    if stream_after <= stream_before:
        raise RuntimeError("stream message not added")

    # locate the EXACT stream message for this admission (fields must match order/user/voucher)
    client = redis_admin(env)
    try:
        order_stream_message_id = None
        for mid, fields in client.xrange("transaction:stream.orders", min="-", max="+"):
            if (
                str(fields.get("id")) == str(order_id)
                and str(fields.get("userId")) == str(user_id)
                and str(fields.get("voucherId")) == str(voucher_id)
            ):
                order_stream_message_id = mid
                break
    finally:
        client.close()
    evidence["order_stream_message_id"] = order_stream_message_id
    if order_stream_message_id is None:
        raise RuntimeError(
            "Drill D FAIL: could not find exact stream message for orderId=" + str(order_id)
        )

    # --- wait <=20s for the EXACT message ID to enter PEL (must not ACK/compensate) ---
    deadline = time.monotonic() + 20
    pending_seen = None
    while time.monotonic() < deadline:
        client = redis_admin(env)
        try:
            p = client.xpending("transaction:stream.orders", "g1")
            cnt = int(p["pending"]) if p else 0
            stock_now = client.get("transaction:seckill:stock:" + str(voucher_id))
            ordered_now = client.scard("transaction:seckill:order:" + str(voucher_id))
            sub_now = client.hgetall("transaction:order:submission:" + str(order_id))
            dlq_now = client.xlen("transaction:stream.orders.dlq")
            exact = None
            for entry in client.xpending_range(
                "transaction:stream.orders", "g1", min="-", max="+", count=200
            ):
                if entry.get("message_id") == order_stream_message_id:
                    exact = {
                        "message_id": entry.get("message_id"),
                        "consumer": entry.get("consumer"),
                        "idle_ms": entry.get("time_since_delivered"),
                        "delivery_count": entry.get("times_delivered"),
                    }
                    break
        finally:
            client.close()
        if exact is not None:
            pending_seen = {
                "pending_group_count": cnt,
                "stock": stock_now,
                "ordered_size": ordered_now,
                "submission_state": sub_now.get("state"),
                "dlq": dlq_now,
                "exact_pending": exact,
            }
            break
        time.sleep(1)
    if pending_seen is None:
        raise RuntimeError(
            "Drill D FAIL: exact stream message " + str(order_stream_message_id)
            + " did not enter PEL within 20s while MySQL down"
        )
    evidence["pending_observed"] = pending_seen
    evidence["order_pending_seen"] = True
    evidence["pending_consumer"] = pending_seen["exact_pending"]["consumer"]
    evidence["pending_idle_ms"] = pending_seen["exact_pending"]["idle_ms"]
    evidence["pending_delivery_count"] = pending_seen["exact_pending"]["delivery_count"]
    if pending_seen["stock"] != "0" or pending_seen["ordered_size"] != 1:
        raise RuntimeError("stock/qualification must not be compensated while MySQL down")
    if pending_seen["submission_state"] == "FAILED":
        raise RuntimeError("submission must not be FAILED while MySQL down")
    if pending_seen["dlq"] != dlq_before:
        raise RuntimeError("DLQ must not grow while MySQL down")

    # --- recovery: start MySQL, wait healthy + convergence (<=60s) ---
    recovery_started_at = now_iso()
    recovery_started_mono = time.monotonic()
    evidence["recovery_started_at"] = recovery_started_at
    compose_ok(["start", "mysql"])
    wait_mysql_ready(env, timeout=60)

    # convergence: mysql order persisted with same orderId, pending=0
    deadline = time.monotonic() + 60
    converged = None
    while time.monotonic() < deadline:
        client = redis_admin(env)
        try:
            p = client.xpending("transaction:stream.orders", "g1")
            cnt = int(p["pending"]) if p else 0
            sub = client.hgetall("transaction:order:submission:" + str(order_id))
            dlq = client.xlen("transaction:stream.orders.dlq")
            stock = client.get("transaction:seckill:stock:" + str(voucher_id))
            ordered = client.scard("transaction:seckill:order:" + str(voucher_id))
            retry = client.hget("transaction:stream.orders:retry", order_stream_message_id)
            order_pending = False
            for entry in client.xpending_range(
                "transaction:stream.orders", "g1", min="-", max="+", count=200
            ):
                if entry.get("message_id") == order_stream_message_id:
                    order_pending = True
                    break
        finally:
            client.close()
        try:
            with mysql_conn(env, "linklife_transaction") as conn, conn.cursor() as cur:
                cur.execute(
                    "SELECT id, user_id, voucher_id FROM tb_voucher_order WHERE id=%s",
                    (order_id,),
                )
                row = cur.fetchone()
                cur.execute(
                    "SELECT COUNT(*) AS orders, COUNT(DISTINCT user_id) AS users FROM tb_voucher_order WHERE voucher_id=%s",
                    (voucher_id,),
                )
                agg = cur.fetchone()
                cur.execute(
                    "SELECT COUNT(*) AS c FROM (SELECT user_id FROM tb_voucher_order WHERE voucher_id=%s "
                    "GROUP BY user_id HAVING COUNT(*) > 1) t",
                    (voucher_id,),
                )
                dup = cur.fetchone()
        except Exception:
            row = agg = dup = None
        if (
            row is not None
            and int(row["id"]) == int(order_id)
            and int(row["user_id"]) == user_id
            and int(row["voucher_id"]) == voucher_id
            and int(agg["orders"]) == 1
            and int(agg["users"]) == 1
            and int(dup["c"]) == 0
            and not order_pending
            and sub.get("state") == "PERSISTED"
            and stock == "0"
            and ordered == 1
            and dlq == dlq_before
            and retry is None
        ):
            converged = {
                "order_row": row,
                "aggregate": agg,
                "duplicate_users": dup["c"],
                "pending_group_count": cnt,
                "order_stream_message_id": order_stream_message_id,
                "order_pending": order_pending,
                "order_pending_after_recovery": order_pending,
                "submission_state": sub.get("state"),
                "stock": stock,
                "ordered_size": ordered,
                "dlq": dlq,
                "retry_marker": retry,
            }
            break
        time.sleep(2)
    if converged is None:
        raise RuntimeError("order did not converge within 60s after MySQL recovery")
    if converged["order_pending_after_recovery"]:
        raise RuntimeError(
            "Drill D FAIL: exact message " + str(order_stream_message_id) + " still in PEL after recovery"
        )
    evidence["converged"] = converged
    evidence["order_pending_after_recovery"] = False
    evidence["convergence_seconds"] = round(time.monotonic() - recovery_started_mono, 2)
    evidence["recovered_at"] = now_iso()
    evidence["verdict"] = "PASS"
    write_evidence("drill-d", "evidence.json", evidence)
    print(
        "drill D PASS: orderId=" + str(order_id) + " convergence=" + str(evidence["convergence_seconds"])
        + "s pending=0 stock=0 dup=0 dlq_unchanged"
    )
    return evidence
