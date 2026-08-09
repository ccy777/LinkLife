"""Stage6B fault-drill orchestrator.

Commands:
    gen-env        generate gitignored performance-test/stage6b.env (random dev secrets)
    bootstrap      build/start linklife-stage6b stack, wait ready, seed users/tokens/extra data
    drill-a        Gateway Sentinel precise rate limiting
    drill-b        Identity outage / breaker / display vs required / recovery
    drill-c        Redis kill/remove/recreate + AOF/volume persistence + reconnect
    drill-d        MySQL-down accepted seckill -> Pending retained -> recovery persist
    summary        aggregate drill verdicts into .linklife-local/evidence/stage6b/summary.json

Only linklife-stage6b-* resources are touched. No secrets are printed.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

import common
from common import (
    API,
    EVIDENCE_ROOT,
    GATEWAY,
    NACOS,
    SCRIPT_DIR,
    compose_ok,
    gen_env,
    load_env,
    nacos_service_ready,
    seed_extra,
    wait_gateway_ready,
    wait_http,
)


def cmd_gen_env(args: argparse.Namespace) -> int:
    gen_env(force=args.force)
    return 0


def cmd_bootstrap(args: argparse.Namespace) -> int:
    env = load_env()
    if not args.skip_build:
        result = compose_ok(["build"], timeout=1800)
        print(result[-2000:] if result else "build ok")
    compose_ok(["up", "-d"], timeout=600)
    if not wait_http(f"{NACOS}/nacos/v1/ns/service/list?pageNo=1&pageSize=5", timeout=180):
        raise RuntimeError("nacos not healthy within 180s")
    for service in common.SERVICE_NAMES:
        if not nacos_service_ready(service, timeout=180):
            raise RuntimeError(f"{service} not registered within 180s")
    if not wait_gateway_ready(timeout=120):
        raise RuntimeError("gateway not ready within 120s")

    run_py(["performance-test/stage6a/seed_data.py", "--count", "520",
            "--mysql-password", env["MYSQL_ROOT_PASSWORD"]])
    seed_extra(env)
    if not args.skip_tokens:
        run_py(["performance-test/stage6a/prepare_tokens.py", "--limit", "530", "--workers", "10",
                "--mysql-password", env["MYSQL_ROOT_PASSWORD"],
                "--redis-password", env["REDIS_ADMIN_PASSWORD"]])
    evidence = EVIDENCE_ROOT / "environment.txt"
    common.write_text(evidence, environment_text())
    print(f"bootstrap complete; environment at {evidence}")
    return 0


def environment_text() -> str:
    lines = [f"captured_at: {common.now_iso()}"]
    for label, cmd in [
        ("git_branch", ["git", "branch", "--show-current"]),
        ("git_commit", ["git", "rev-parse", "HEAD"]),
        ("docker_version", ["docker", "--version"]),
        ("java_version", ["java", "-version"]),
        ("python_version", ["python", "--version"]),
    ]:
        try:
            r = subprocess.run(cmd, capture_output=True, text=True, timeout=30, cwd=str(common.REPO_ROOT))
            lines.append(f"{label}: {(r.stdout or r.stderr).strip()[:200]}")
        except Exception as exc:
            lines.append(f"{label}: error {exc}")
    return "\n".join(lines) + "\n"


def run_py(args: list[str]) -> None:
    subprocess.run(["py", *args], check=True, timeout=900, cwd=str(common.REPO_ROOT))


def cmd_drill(env: dict, module: str, args: argparse.Namespace) -> int:
    mod = __import__(module)
    result = mod.run(env)
    print(json.dumps({k: v for k, v in result.items() if k != "samples"}, ensure_ascii=False)[:600])
    return 0 if result.get("verdict") == "PASS" else 3


def cmd_summary(args: argparse.Namespace) -> int:
    summary = {"generated_at": common.now_iso(), "drills": {}}
    for name in ("drill-a", "drill-b", "drill-c", "drill-d"):
        path = EVIDENCE_ROOT / name / "evidence.json"
        if path.exists():
            data = json.loads(path.read_text(encoding="utf-8"))
            summary["drills"][name] = {
                "verdict": data.get("verdict"),
                "started_at": data.get("started_at"),
                "finished_at": data.get("finished_at", data.get("recovered_at")),
                "recovery_seconds": data.get("recovery_seconds"),
            }
    summary["all_pass"] = all(
        d.get("verdict") == "PASS" for d in summary["drills"].values()
    ) and len(summary["drills"]) == 4
    (EVIDENCE_ROOT / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0 if summary["all_pass"] else 3


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Stage6B fault drills")
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("gen-env")
    p.add_argument("--force", action="store_true")
    p = sub.add_parser("bootstrap")
    p.add_argument("--skip-build", action="store_true")
    p.add_argument("--skip-tokens", action="store_true")
    for name in ("drill-a", "drill-b", "drill-c", "drill-d"):
        sub.add_parser(name)
    sub.add_parser("summary")
    return parser


def main() -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    args = build_parser().parse_args()
    try:
        if args.command == "gen-env":
            return cmd_gen_env(args)
        if args.command == "bootstrap":
            return cmd_bootstrap(args)
        if args.command == "summary":
            return cmd_summary(args)
        env = load_env()
        module = {
            "drill-a": "drill_a",
            "drill-b": "drill_b",
            "drill-c": "drill_c",
            "drill-d": "drill_d",
        }[args.command]
        return cmd_drill(env, module, args)
    except Exception as exc:
        print(f"ERROR: {type(exc).__name__}: {exc}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
