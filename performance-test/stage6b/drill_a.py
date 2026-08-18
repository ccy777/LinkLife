"""Drill A: Gateway Sentinel precise rate limiting.

Isolated local fault-drill low thresholds (hot-blog-qps=2, shop-of-type-qps=2, seckill-qps=2).
Verifies: hotspot bursts produce HTTP 429 with success=false and the fixed
error message, no 5xx, non-hotspot endpoints are not affected, and seckill
requests blocked by 429 do not generate orders/submissions.
"""

from __future__ import annotations

import time

from common import (
    API,
    EVIDENCE_ROOT,
    now_iso,
    http_json,
    mysql_conn,
    redis_admin,
    token_for_phone,
    utc_naive,
    wait_gateway_ready,
    write_evidence,
    ADMIN_PHONE,
)


def create_voucher(env: dict, admin_token: str, stock: int = 10) -> int:
    body = {
        "shopId": 1,
        "title": "drill-a-" + str(int(time.time())),
        "subTitle": "stage6b drill-a",
        "rules": "test-only",
        "payValue": 100,
        "actualValue": 1000,
        "type": 1,
        "status": 1,
        "stock": stock,
        "beginTime": utc_naive(-1),
        "endTime": utc_naive(120),
    }
    result = http_json("POST", "/voucher/seckill", token=admin_token, body=body)
    if result["status"] != 200 or result["body"].get("success") is not True:
        raise RuntimeError("voucher create failed: " + str(result))
    return int(result["body"]["data"])


def wait_initialized(env: dict, voucher_id: int, stock: int, timeout: int = 60) -> bool:
    client = redis_admin(env)
    try:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if client.get("transaction:seckill:stock:" + str(voucher_id)) == str(stock):
                return True
            time.sleep(1)
    finally:
        client.close()
    return False


def burst(method: str, path: str, count: int = 30, token=None, body=None) -> dict:
    codes: dict[int, int] = {}
    business_ok = 0
    blocked_msg_ok = 0
    samples = []
    for _ in range(count):
        r = http_json(method, path, token=token, body=body)
        codes[r["status"]] = codes.get(r["status"], 0) + 1
        if r["body"].get("success") is True:
            business_ok += 1
        if r["status"] == 429 and r["body"].get("errorMsg") == "请求过于频繁，请稍后再试":
            blocked_msg_ok += 1
        samples.append({
            "status": r["status"],
            "success": r["body"].get("success"),
            "errorMsg": r["body"].get("errorMsg"),
        })
        time.sleep(0.05)
    return {
        "sent": count,
        "codes": codes,
        "http_429": codes.get(429, 0),
        "http_5xx": sum(v for k, v in codes.items() if k >= 500),
        "business_ok": business_ok,
        "blocked_msg_ok": blocked_msg_ok,
        "samples": samples,
    }


def run(env: dict) -> dict:
    if not wait_gateway_ready():
        raise RuntimeError("gateway not ready")
    admin_token = token_for_phone(ADMIN_PHONE)
    user_token = token_for_phone("19900000001")

    voucher_id = create_voucher(env, admin_token)
    if not wait_initialized(env, voucher_id, 10):
        raise RuntimeError("voucher " + str(voucher_id) + " not initialized")

    with mysql_conn(env, "linklife_transaction") as conn, conn.cursor() as cur:
        cur.execute("SELECT COUNT(*) AS c FROM tb_voucher_order WHERE voucher_id=%s", (voucher_id,))
        orders_before = int(cur.fetchone()["c"])

    evidence = {
        "drill": "A",
        "started_at": now_iso(),
        "voucher_id": voucher_id,
        "orders_before": orders_before,
    }

    non_hotspot = {
        "GET /api/blog/4": http_json("GET", "/blog/4"),
        "POST /api/blog": http_json(
            "POST", "/blog", token=user_token,
            body={"shopId": 1, "title": "drill-a-blog", "images": "x", "content": "drill-a"},
        ),
        "POST /api/shop": http_json(
            "POST", "/shop", token=admin_token,
            body={
                "name": "drill-a-shop", "typeId": 1, "images": "x", "area": "x",
                "address": "x", "x": 0, "y": 0, "avgPrice": 1, "sold": 0,
                "comments": 0, "score": 50, "openHours": "10:00-22:00",
            },
        ),
        "POST /api/voucher-order/12345/cancel": http_json(
            "POST", "/voucher-order/12345/cancel", token=user_token
        ),
    }
    evidence["non_hotspot"] = non_hotspot
    for label, r in non_hotspot.items():
        if r["status"] == 429 or r["status"] >= 500:
            raise RuntimeError("non-hotspot " + label + " wrongly limited/errored: " + str(r))

    blog = burst("GET", "/blog/hot?current=1")
    of_type = burst("GET", "/shop/of/type?typeId=1&current=1")
    seckill = burst("POST", "/voucher-order/seckill/" + str(voucher_id), token=user_token)
    evidence["hotspot"] = {"blog_hot": blog, "shop_of_type": of_type, "seckill": seckill}
    evidence["seckill"] = seckill

    for name, b in (("blog_hot", blog), ("shop_of_type", of_type), ("seckill", seckill)):
        if b["http_429"] < 1:
            raise RuntimeError("drill A: " + name + " produced no 429: " + str(b["codes"]))
        if b["http_5xx"] != 0:
            raise RuntimeError("drill A: " + name + " produced 5xx: " + str(b["codes"]))
        if b["blocked_msg_ok"] != b["http_429"]:
            raise RuntimeError("drill A: " + name + " 429 message mismatch")

    with mysql_conn(env, "linklife_transaction") as conn, conn.cursor() as cur:
        cur.execute("SELECT COUNT(*) AS c FROM tb_voucher_order WHERE voucher_id=%s", (voucher_id,))
        orders_after = int(cur.fetchone()["c"])
    client = redis_admin(env)
    try:
        ordered_size = client.scard("transaction:seckill:order:" + str(voucher_id))
    finally:
        client.close()
    order_delta = orders_after - orders_before
    accepted = seckill["business_ok"]
    evidence["seckill"]["orders_after"] = orders_after
    evidence["seckill"]["order_delta"] = order_delta
    evidence["seckill"]["redis_ordered_size"] = ordered_size
    evidence["seckill"]["accepted"] = accepted

    if order_delta != accepted:
        raise RuntimeError(
            "drill A: order_delta=" + str(order_delta) + " != accepted=" + str(accepted)
            + "; 429 requests must not generate orders"
        )
    if ordered_size != accepted:
        raise RuntimeError(
            "drill A: redis ordered size " + str(ordered_size) + " != accepted " + str(accepted)
        )

    evidence["finished_at"] = now_iso()
    evidence["verdict"] = "PASS"
    write_evidence("drill-a", "evidence.json", evidence)
    print(
        "drill A PASS: blog429=" + str(blog["http_429"]) + " oftype429=" + str(of_type["http_429"])
        + " seckill429=" + str(seckill["http_429"]) + " 5xx=0 order_delta=" + str(order_delta)
        + " accepted=" + str(accepted)
    )
    return evidence
