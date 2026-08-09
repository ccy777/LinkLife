"""Drill B: Identity outage / Sentinel breaker / display degradation / required fail-closed / recovery."""

from __future__ import annotations

import time

from common import (
    API,
    now_iso,
    http_json,
    nacos_service_ready,
    token_for_phone,
    wait_gateway_ready,
    write_evidence,
    ADMIN_PHONE,
    compose,
    compose_ok,
)

UNAVAILABLE = "用户服务暂时不可用，请稍后再试"
NOT_FOUND = "目标用户不存在"


def robust(method: str, path: str, token=None, body=None) -> dict:
    """One retry for transport-level timeouts (stale pooled connection after fault)."""
    r = http_json(method, path, token=token, body=body)
    if r.get("transport_error"):
        r2 = http_json(method, path, token=token, body=body)
        r2["retried_after_transport_error"] = True
        return r2
    return r


def run(env: dict) -> dict:
    if not wait_gateway_ready():
        raise RuntimeError("gateway not ready")
    admin_token = token_for_phone(ADMIN_PHONE)
    user4_token = token_for_phone("13456789011")

    evidence = {"drill": "B", "started_at": now_iso()}

    # --- baseline (identity healthy) ---
    hot = robust("GET", "/blog/hot?current=1")
    if hot["status"] != 200 or hot["body"].get("success") is not True:
        raise RuntimeError("baseline blog/hot failed: " + str(hot))
    display_names = [b.get("name") for b in hot["body"].get("data") or []]
    evidence["baseline_blog_hot"] = {
        "status": hot["status"],
        "blogs": [{k: b.get(k) for k in ("id", "content", "userId", "name", "icon")} for b in hot["body"]["data"]],
    }
    if not any(display_names):
        raise RuntimeError("baseline blog/hot has no name/icon (identity should be healthy)")

    missing = robust("PUT", "/follow/99999999/true", token=admin_token)
    if missing["body"].get("errorMsg") != NOT_FOUND:
        raise RuntimeError("baseline missing-user check failed: " + str(missing))
    common = robust("GET", "/follow/common/4", token=admin_token)
    common_ids = [u.get("id") for u in (common["body"].get("data") or [])]
    likes = robust("GET", "/blog/likes/4", token=admin_token)
    likes_ids = [u.get("id") for u in (likes["body"].get("data") or [])]
    evidence["baseline_required"] = {
        "missing_user": missing,
        "follow_common": {"status": common["status"], "ids": common_ids},
        "blog_likes": {"status": likes["status"], "ids": likes_ids},
    }
    if 3 not in common_ids or len(likes_ids) < 1:
        raise RuntimeError("baseline required RPCs did not return identity data: " + str(evidence["baseline_required"]))

    # --- fault: stop identity-service ---
    fault_at = now_iso()
    evidence["fault_detected_at"] = fault_at
    compose_ok(["stop", "identity-service"])

    # trigger breaker: repeated required RPC failures (>=5)
    trigger = []
    for _ in range(8):
        r = robust("PUT", "/follow/2/true", token=admin_token)
        trigger.append({"status": r["status"], "errorMsg": r["body"].get("errorMsg")})
        time.sleep(0.2)
    evidence["breaker_trigger"] = trigger
    if sum(1 for t in trigger if t["errorMsg"] == UNAVAILABLE) < 5:
        raise RuntimeError("breaker trigger did not fail closed (>=5 unavailable): " + str(trigger))

    # --- while breaker OPEN ---
    time.sleep(1)
    hot_open = robust("GET", "/blog/hot?current=1")
    open_blogs = [
        {k: b.get(k) for k in ("id", "content", "userId", "name", "icon")}
        for b in (hot_open["body"].get("data") or [])
    ]
    follow_open = robust("PUT", "/follow/2/true", token=admin_token)
    common_open = robust("GET", "/follow/common/4", token=admin_token)
    likes_open = robust("GET", "/blog/likes/4", token=admin_token)
    evidence["open_behavior"] = {
        "blog_hot": {"status": hot_open["status"], "success": hot_open["body"].get("success"), "blogs": open_blogs},
        "follow_target": follow_open,
        "follow_common": common_open,
        "blog_likes": likes_open,
    }
    if hot_open["status"] != 200 or hot_open["body"].get("success") is not True:
        raise RuntimeError("blog/hot must stay 200 during outage: " + str(hot_open))
    if not open_blogs or open_blogs[0].get("id") is None or open_blogs[0].get("content") is None:
        raise RuntimeError("blog body missing during outage")
    if any(b.get("name") is not None or b.get("icon") is not None for b in open_blogs):
        raise RuntimeError("blog name/icon must be absent (no fake user) during outage")
    for name, r in (("follow_target", follow_open), ("follow_common", common_open), ("blog_likes", likes_open)):
        if r["body"].get("errorMsg") != UNAVAILABLE:
            raise RuntimeError(name + " must fail closed with unavailable message: " + str(r))

    # fast-fail latency (breaker open -> no RPC wait)
    t0 = time.monotonic()
    fast = robust("PUT", "/follow/2/true", token=admin_token)
    fast_ms = round((time.monotonic() - t0) * 1000, 1)
    evidence["fast_fail"] = {"elapsed_ms": fast_ms, "response": fast}
    # reproducible unit-test evidence for Feign invocation freeze:
    evidence["fast_fail_unit_test"] = (
        "IdentityUserDirectoryTest.breakerOpenStopsFeignCallsForDisplayAndRequired "
        "(Feign call counter stops increasing once OPEN)"
    )

    # --- recovery ---
    recovery_started_at = now_iso()
    recovery_started_mono = time.monotonic()
    evidence["recovery_started_at"] = recovery_started_at
    compose_ok(["start", "identity-service"])
    if not nacos_service_ready("linklife-identity-service", timeout=60):
        raise RuntimeError("identity did not register within 60s")
    if not wait_gateway_ready(timeout=60):
        raise RuntimeError("gateway not ready after identity recovery")
    time.sleep(6)  # breaker time window (5s) + margin
    # half-open probe via display, then verify closure
    probe = robust("GET", "/blog/hot?current=1")
    recovered_names = [b.get("name") for b in (probe["body"].get("data") or [])]
    follow_missing = robust("PUT", "/follow/99999999/true", token=admin_token)
    common_rec = robust("GET", "/follow/common/4", token=admin_token)
    likes_rec = robust("GET", "/blog/likes/4", token=admin_token)
    recovered_at = now_iso()
    recovery_seconds = round(time.monotonic() - recovery_started_mono, 2)
    evidence["recovered_at"] = recovered_at
    evidence["recovery_seconds"] = recovery_seconds
    evidence["recovery"] = {
        "blog_hot_names_present": any(recovered_names),
        "follow_missing": follow_missing,
        "follow_common": common_rec,
        "blog_likes": likes_rec,
    }
    if not any(recovered_names):
        raise RuntimeError("blog name/icon did not recover: " + str(probe))
    if follow_missing["body"].get("errorMsg") != NOT_FOUND:
        raise RuntimeError("missing-user must return 目标用户不存在 after recovery: " + str(follow_missing))
    rec_common_ids = [u.get("id") for u in (common_rec["body"].get("data") or [])]
    rec_likes_ids = [u.get("id") for u in (likes_rec["body"].get("data") or [])]
    if 3 not in rec_common_ids or len(rec_likes_ids) < 1:
        raise RuntimeError("required RPCs did not recover: " + str(evidence["recovery"]))

    evidence["verdict"] = "PASS"
    write_evidence("drill-b", "evidence.json", evidence)
    print("drill B PASS: recovery_seconds=" + str(evidence["recovery_seconds"]))
    return evidence
