"""Drill C: Redis kill/remove/recreate + AOF/volume persistence and service reconnect."""

from __future__ import annotations

import socket
import time

from common import (
    now_iso,
    http_json,
    mysql_conn,
    nacos_service_ready,
    redis_admin,
    redis_user,
    token_for_phone,
    wait_gateway_ready,
    write_evidence,
    ADMIN_PHONE,
    compose,
    compose_ok,
)

TEST_SUBMISSION = "987654321"
TEST_STREAM_ID = "887766554433221100"


def raw_redis_cmd(env: dict, username: str, password: str, *args) -> bytes:
    """Issue one command as an ACL user over a raw socket (avoids redis-py auth quirk)."""
    s = socket.create_connection(("127.0.0.1", 16379), timeout=5)
    try:
        f = s.makefile("rwb", buffering=0)
        f.write(b"AUTH " + username.encode() + b" " + password.encode() + b"\r\n")
        reply = f.readline().strip()
        if reply != b"+OK":
            raise RuntimeError("AUTH failed for " + username + ": " + reply[:120].decode(errors="replace"))
        cmd = b" ".join(a.encode() if isinstance(a, str) else a for a in args) + b"\r\n"
        f.write(cmd)
        return f.readline().strip()
    finally:
        s.close()


def run(env: dict) -> dict:
    if not wait_gateway_ready():
        raise RuntimeError("gateway not ready")
    admin_token = token_for_phone(ADMIN_PHONE)
    evidence = {"drill": "C", "started_at": now_iso()}

    # --- seed representative state (transaction paused so consumer c1 cannot steal) ---
    compose_ok(["stop", "transaction-service"])
    time.sleep(3)
    client = redis_admin(env)
    try:
        # idempotent cleanup of any previous partial run
        for key in (
            "identity:drill-c:test",
            "merchant:cache:shop:999999999",
            "social:drill-c:test",
            "transaction:order:submission:" + TEST_SUBMISSION,
        ):
            client.delete(key)
        # exact PEL cleanup: XACK first, then XDEL, for any drill-c test messages
        for mid in client.xrange("transaction:stream.orders", min="-", max="+"):
            fields = mid[1]
            if str(fields.get("voucherId")) == "999999999" or str(fields.get("id")) == TEST_STREAM_ID:
                client.xack("transaction:stream.orders", "g1", mid[0])
                client.xdel("transaction:stream.orders", mid[0])
        try:
            client.xgroup_delconsumer("transaction:stream.orders", "g1", "drillc")
        except Exception:
            pass
        # precondition: PEL must be clean before injecting a new test message
        pending = client.xpending("transaction:stream.orders", "g1")
        pending_count = int(pending["pending"]) if pending else 0
        groups = client.xinfo_groups("transaction:stream.orders")
        g1 = next((g for g in groups if g.get("name") == "g1"), {})
        if pending_count != 0 or int(g1.get("lag", -1)) != 0:
            raise RuntimeError(
                "Drill C precondition FAILED: group PEL/lag not clean "
                "(pending=" + str(pending_count) + ", lag=" + str(g1.get("lag")) + ")"
            )
        evidence["pending_baseline"] = pending_count
        client.set("identity:drill-c:test", "drill-c-identity", ex=3600)
        client.set("merchant:cache:shop:999999999", "drill-c-merchant", ex=600)
        client.set("social:drill-c:test", "drill-c-social", ex=600)
        client.hset(
            "transaction:order:submission:" + TEST_SUBMISSION,
            mapping={
                "state": "ACCEPTED",
                "userId": "1",
                "voucherId": "999999999",
                "message": "drill-c-test",
                "updatedAt": str(int(time.time() * 1000)),
            },
        )
        client.expire("transaction:order:submission:" + TEST_SUBMISSION, 3600)
        test_stream_message_id = client.xadd(
            "transaction:stream.orders",
            {"userId": "1", "voucherId": "999999999", "id": TEST_STREAM_ID},
        )
        evidence["test_stream_message_id"] = test_stream_message_id
        # claim the message for consumer "drillc" -> deterministic pending entry
        claimed = client.xreadgroup("g1", "drillc", {"transaction:stream.orders": ">"}, count=1)
        claimed_id = None
        if claimed:
            for _, entries in claimed:
                if entries:
                    claimed_id = entries[0][0]
        if claimed_id != test_stream_message_id:
            raise RuntimeError(
                "Drill C FAIL: XREADGROUP returned " + str(claimed_id)
                + " instead of test_stream_message_id " + str(test_stream_message_id)
            )
        evidence["claimed_id_matches"] = True
    finally:
        client.close()
    compose_ok(["start", "transaction-service"])
    if not nacos_service_ready("linklife-transaction-service", timeout=60):
        raise RuntimeError("transaction service did not recover after seeding")

    def snapshot() -> dict:
        c = redis_admin(env)
        try:
            session_key = "identity:login:token:" + admin_token
            pending = c.xpending("transaction:stream.orders", "g1")
            pending_count = int(pending["pending"]) if pending else 0
            exact = None
            for entry in c.xpending_range(
                "transaction:stream.orders", "g1", min="-", max="+", count=200
            ):
                if entry.get("message_id") == test_stream_message_id:
                    exact = {
                        "message_id": entry.get("message_id"),
                        "consumer": entry.get("consumer"),
                        "idle_ms": entry.get("time_since_delivered"),
                        "delivery_count": entry.get("times_delivered"),
                    }
                    break
            return {
                "identity_drill_key": {"value": c.get("identity:drill-c:test"), "ttl": c.ttl("identity:drill-c:test")},
                "session_key_exists": c.exists(session_key),
                "session_ttl": c.ttl(session_key),
                "submission": c.hgetall("transaction:order:submission:" + TEST_SUBMISSION),
                "submission_ttl": c.ttl("transaction:order:submission:" + TEST_SUBMISSION),
                "stream_xlen": c.xlen("transaction:stream.orders"),
                "pending_count": pending_count,
                "exact_pending": exact,
                "groups": c.xinfo_groups("transaction:stream.orders"),
                "merchant_key": {"value": c.get("merchant:cache:shop:999999999"), "ttl": c.ttl("merchant:cache:shop:999999999")},
                "social_key": {"value": c.get("social:drill-c:test"), "ttl": c.ttl("social:drill-c:test")},
            }
        finally:
            c.close()

    before = snapshot()
    evidence["before"] = before
    evidence["pending_before_exact"] = before["exact_pending"] is not None
    evidence["pending_consumer_before"] = (
        before["exact_pending"]["consumer"] if before["exact_pending"] else None
    )
    if before["exact_pending"] is None:
        raise RuntimeError("Drill C FAIL: test_stream_message_id not in PEL before restart")
    # wait >=2s so everysec AOF has a chance to flush
    time.sleep(3)

    # --- fault: kill / rm / recreate redis (named volume preserved) ---
    redis_down_at = now_iso()
    redis_down_mono = time.monotonic()
    evidence["redis_down_at"] = redis_down_at
    compose_ok(["kill", "redis"])
    compose_ok(["rm", "-f", "redis"])
    compose_ok(["up", "-d", "redis"])

    # wait ready: container healthy OR admin PING succeeds (healthcheck cadence may lag)
    deadline = time.monotonic() + 90
    ready_at = None
    container_healthy_at = None
    while time.monotonic() < deadline:
        r = compose(["ps", "--filter", "name=redis", "--format", "{{.Status}}"])
        if "healthy" in r.stdout and container_healthy_at is None:
            container_healthy_at = now_iso()
        try:
            c = redis_admin(env)
            try:
                if c.ping():
                    ready_at = now_iso()
                    break
            finally:
                c.close()
        except Exception:
            pass
        time.sleep(2)
    if ready_at is None:
        raise RuntimeError("redis did not become ready within 90s")
    redis_healthy_at = now_iso()
    evidence["redis_ready_at"] = ready_at
    evidence["redis_container_healthy_at"] = container_healthy_at
    evidence["redis_healthy_at"] = redis_healthy_at

    after = snapshot()
    evidence["after"] = after
    evidence["pending_after_exact"] = after["exact_pending"] is not None
    evidence["pending_consumer_after"] = (
        after["exact_pending"]["consumer"] if after["exact_pending"] else None
    )
    if after["exact_pending"] is None:
        raise RuntimeError(
            "Drill C FAIL: test_stream_message_id " + str(test_stream_message_id)
            + " not in PEL after restart"
        )
    if after["exact_pending"]["message_id"] != before["exact_pending"]["message_id"]:
        raise RuntimeError("Drill C FAIL: exact pending message id changed across restart")
    if after["identity_drill_key"]["value"] != before["identity_drill_key"]["value"]:
        raise RuntimeError("identity test key not preserved")
    if after["identity_drill_key"]["ttl"] <= 0 or after["identity_drill_key"]["ttl"] > before["identity_drill_key"]["ttl"]:
        raise RuntimeError("identity test key TTL not positive/decreasing")
    if not after["session_key_exists"] or after["session_ttl"] <= 0:
        raise RuntimeError("identity session not preserved")
    if after["submission"].get("state") != "ACCEPTED":
        raise RuntimeError("transaction submission not preserved")
    if after["submission_ttl"] <= 0:
        raise RuntimeError("submission TTL not preserved")
    if after["stream_xlen"] != before["stream_xlen"]:
        raise RuntimeError("stream XLEN changed: " + str(before["stream_xlen"]) + " -> " + str(after["stream_xlen"]))
    if not any(g.get("name") == "g1" for g in after["groups"]):
        raise RuntimeError("consumer group g1 missing after restart")
    if after["merchant_key"]["value"] != before["merchant_key"]["value"] or after["merchant_key"]["ttl"] <= 0:
        raise RuntimeError("merchant representative key not preserved")
    if after["social_key"]["value"] != before["social_key"]["value"] or after["social_key"]["ttl"] <= 0:
        raise RuntimeError("social representative key not preserved")

    # --- ACL: cross-namespace must be NOPERM ---
    acl = {}
    try:
        reply = raw_redis_cmd(
            env, "linklife_merchant", env["REDIS_MERCHANT_PASSWORD"],
            "GET", "identity:login:token:x",
        )
        acl["merchant_read_identity"] = reply.decode(errors="replace")
    except Exception as exc:
        acl["merchant_read_identity"] = str(exc).splitlines()[0][:200]
    evidence["acl"] = acl
    if "NOPERM" not in acl["merchant_read_identity"]:
        raise RuntimeError("ACL cross-namespace check failed: " + acl["merchant_read_identity"])

    # --- service functional ---
    me = http_json("GET", "/user/me", token=admin_token)
    shop = http_json("GET", "/shop/1")
    submission = http_json("GET", "/voucher-order/submissions/" + TEST_SUBMISSION, token=admin_token)
    like = http_json("PUT", "/blog/like/4", token=admin_token)
    service_functional_at = now_iso()
    evidence["service_functional_at"] = service_functional_at
    evidence["service_checks"] = {
        "gateway_session_me": {"status": me["status"], "success": me["body"].get("success")},
        "merchant_shop": {"status": shop["status"], "success": shop["body"].get("success")},
        "transaction_submission": {"status": submission["status"], "body": submission["body"]},
        "social_like": {"status": like["status"], "success": like["body"].get("success")},
    }
    if me["status"] != 200 or me["body"].get("success") is not True:
        raise RuntimeError("gateway session not usable after redis restart")
    if shop["status"] != 200 or shop["body"].get("success") is not True:
        raise RuntimeError("merchant query failed after redis restart")
    if submission["body"].get("data", {}).get("state") != "ACCEPTED":
        raise RuntimeError("transaction submission status not readable after redis restart")
    if like["status"] != 200 or like["body"].get("success") is not True:
        raise RuntimeError("social redis access failed after redis restart")

    evidence["recovery_seconds"] = round(time.monotonic() - redis_down_mono, 2)
    evidence["verdict"] = "PASS"
    write_evidence("drill-c", "evidence.json", evidence)
    print("drill C PASS: pending=" + str(after["pending_count"]) + " xlen=" + str(after["stream_xlen"]))
    return evidence
