#!/usr/bin/env sh
# 018F one-shot Stage3 -> Stage4 Redis quiescent cutover tool.
#
# Rules (frozen):
#   - runs only against Redis DB 0;
#   - RENAMENX only: preserves value/type/TTL (Stream rename preserves data,
#     consumer groups, PEL); fails closed when the target key already exists;
#   - preflight scans every source key, verifies targets are absent, verifies no
#     active legacy locks, prints key counts/categories (never raw keys or values),
#     and requires LINKLIFE_REDIS_CUTOVER_CONFIRM=STAGE3_TO_STAGE4;
#   - any conflict/lock aborts with zero renames;
#   - deprecated social keys (follows:*/blog:liked:*/feed:*) are only reported,
#     never migrated or deleted; Stage4 never reads them.
#
# Secret handling:
#   - the admin password is passed to redis-cli through the REDISCLI_AUTH
#     environment variable, never via -a <password> on the command line;
#   - all raw-key temporary state lives in a private mktemp WORKDIR created with
#     umask 077 (owner read/write only) and is removed on normal exit, preflight
#     failure, migration failure, SIGINT and SIGTERM; cleanup is scoped strictly
#     to the mktemp-created directory for this task.
set -eu

: "${LINKLIFE_REDIS_ADMIN_HOST:?required}"
: "${LINKLIFE_REDIS_ADMIN_PORT:?required}"
: "${LINKLIFE_REDIS_ADMIN_PASSWORD:?required}"

if [ "${LINKLIFE_REDIS_CUTOVER_CONFIRM:-}" != "STAGE3_TO_STAGE4" ]; then
  echo "[preflight] LINKLIFE_REDIS_CUTOVER_CONFIRM must equal STAGE3_TO_STAGE4" >&2
  exit 1
fi

run_redis() {
  REDISCLI_AUTH="${LINKLIFE_REDIS_ADMIN_PASSWORD}" redis-cli \
    -h "${LINKLIFE_REDIS_ADMIN_HOST}" -p "${LINKLIFE_REDIS_ADMIN_PORT}" \
    --no-auth-warning -n 0 "$@"
}

umask 077
WORKDIR="$(mktemp -d "${TMPDIR:-/tmp}/linklife-redis-migrate.XXXXXX")"
STATE="${WORKDIR}/state"
PATTERNS_FILE="${WORKDIR}/patterns"
SCAN_FILE="${WORKDIR}/scan.tmp"
LOCK_SCAN_FILE="${WORKDIR}/lock-scan.tmp"
: > "${STATE}"
: > "${PATTERNS_FILE}"

cleanup() {
  rm -rf -- "${WORKDIR}"
}
trap 'cleanup' EXIT
trap 'exit 1' INT TERM

IDENTITY_PATTERNS="login:token:*
login:code:*
login:code:cooldown:*
login:code:attempt:*
sign:*"
MERCHANT_PATTERNS="shop:geo:*"
TRANSACTION_PATTERNS="seckill:stock:*
seckill:order:*
seckill:begin:*
seckill:end:*
seckill:init:marker:*
order:submission:*
order:close:comp:*
order:create:comp:*
icr:order:*"
EXACT_TRANSACTION="stream.orders
stream.orders.dlq
stream.orders:dlq:written
stream.orders:retry"
ACTIVE_LOCKS="lock:order:*
lock:login:code:*
lock:blog:like:*"
DEPRECATED_PATTERNS="follows:*
blog:liked:*
feed:*"

category_of() {
  case "$1" in
    identity:*) echo "identity" ;;
    merchant:*) echo "merchant" ;;
    transaction:*) echo "transaction" ;;
    *) echo "unknown" ;;
  esac
}

lock_category_of() {
  case "$1" in
    lock:order:*) echo "order-lock" ;;
    lock:login:code:*) echo "login-code-lock" ;;
    lock:blog:like:*) echo "blog-like-lock" ;;
    *) echo "unknown-lock" ;;
  esac
}

target_prefix() {
  src="$1"
  case "${src}" in
    login:token:*|login:code:*|login:code:cooldown:*|login:code:attempt:*|sign:*)
      printf 'identity:%s\n' "${src}"; return 0 ;;
    shop:geo:*)
      printf 'merchant:%s\n' "${src}"; return 0 ;;
    seckill:stock:*|seckill:order:*|seckill:begin:*|seckill:end:*|seckill:init:marker:*|order:submission:*|order:close:comp:*|order:create:comp:*|icr:order:*)
      printf 'transaction:%s\n' "${src}"; return 0 ;;
    stream.orders|stream.orders.dlq|stream.orders:dlq:written|stream.orders:retry)
      printf 'transaction:%s\n' "${src}"; return 0 ;;
  esac
  return 1
}

echo "[preflight] confirming Redis DB 0"
run_redis SELECT 0 >/dev/null

echo "[preflight] checking active legacy locks"
lock_conflicts=0
printf '%s\n' "${ACTIVE_LOCKS}" > "${PATTERNS_FILE}"
while read -r pattern; do
  [ -n "${pattern}" ] || continue
  run_redis --scan --pattern "${pattern}" > "${LOCK_SCAN_FILE}"
  count=0
  while read -r key; do
    [ -n "${key}" ] || continue
    count=$((count + 1))
  done < "${LOCK_SCAN_FILE}"
  if [ "${count}" -ne 0 ]; then
    echo "[preflight] FAIL active legacy lock category=$(lock_category_of "${pattern}") count=${count}" >&2
    lock_conflicts=$((lock_conflicts + count))
  fi
done < "${PATTERNS_FILE}"
if [ "${lock_conflicts}" -ne 0 ]; then
  exit 1
fi

echo "[preflight] scanning source keys and verifying absent targets"
total=0
conflicts=0
{
  printf '%s\n' "${IDENTITY_PATTERNS}"
  printf '%s\n' "${MERCHANT_PATTERNS}"
  printf '%s\n' "${TRANSACTION_PATTERNS}"
  printf '%s\n' "${EXACT_TRANSACTION}"
} > "${PATTERNS_FILE}"
while read -r pattern; do
  [ -n "${pattern}" ] || continue
  run_redis --scan --pattern "${pattern}" > "${SCAN_FILE}"
  while read -r key; do
    [ -n "${key}" ] || continue
    dst=$(target_prefix "${key}" || true)
    if [ -z "${dst}" ]; then
      echo "[preflight] FAIL no mapping category=unknown" >&2
      exit 1
    fi
    exists=$(run_redis EXISTS "${dst}")
    if [ "${exists}" -ne 0 ]; then
      echo "[preflight] FAIL target conflict category=$(category_of "${dst}")" >&2
      conflicts=$((conflicts + 1))
      continue
    fi
    type=$(run_redis TYPE "${key}")
    ttl=$(run_redis TTL "${key}")
    printf '%s\t%s\t%s\t%s\t\n' "${key}" "${dst}" "${type}" "${ttl}" >> "${STATE}"
    total=$((total + 1))
  done < "${SCAN_FILE}"
done < "${PATTERNS_FILE}"

# Overlapping patterns (e.g. login:code:* vs login:code:cooldown:*) can surface the
# same key twice; deduplicate before migration so each key is renamed exactly once.
sort -u "${STATE}" -o "${STATE}"

# Record stream metadata before migration.
STATE_NEW="${WORKDIR}/state.new"
: > "${STATE_NEW}"
while IFS="$(printf '\t')" read -r src dst type ttl meta; do
  if [ "${type}" = "stream" ]; then
    xlen=$(run_redis XLEN "${src}")
    groups=$(run_redis XINFO GROUPS "${src}" 2>/dev/null | tr '\n' '|')
    printf '%s\t%s\t%s\t%s\tXLEN=%s;GROUPS=%s\n' "${src}" "${dst}" "${type}" "${ttl}" "${xlen}" "${groups}" >> "${STATE_NEW}"
  else
    printf '%s\t%s\t%s\t%s\t\n' "${src}" "${dst}" "${type}" "${ttl}" >> "${STATE_NEW}"
  fi
done < "${STATE}"
mv "${STATE_NEW}" "${STATE}"

deprecated=0
printf '%s\n' "${DEPRECATED_PATTERNS}" > "${PATTERNS_FILE}"
while read -r pattern; do
  [ -n "${pattern}" ] || continue
  count=$(run_redis --scan --pattern "${pattern}" | grep -c . || true)
  deprecated=$((deprecated + count))
done < "${PATTERNS_FILE}"

if [ "${conflicts}" -ne 0 ]; then
  echo "[preflight] FAIL ${conflicts} target conflict(s); aborting with 0 renames" >&2
  exit 1
fi
total=$(wc -l < "${STATE}")
echo "[preflight] OK keys-to-migrate=${total}, deprecated-social-legacy=${deprecated}"

echo "[migrate] RENAMENX cutover"
failures=0
renamed=0
while IFS="$(printf '\t')" read -r src dst type ttl meta; do
  reply=$(run_redis RENAMENX "${src}" "${dst}")
  if [ "${reply}" != "1" ]; then
    echo "[migrate] FAIL RENAMENX category=$(category_of "${dst}") reply=${reply}" >&2
    failures=$((failures + 1))
  else
    renamed=$((renamed + 1))
  fi
done < "${STATE}"
if [ "${failures}" -ne 0 ]; then
  exit 1
fi

echo "[postflight] verifying migrated keys"
verified=0
while IFS="$(printf '\t')" read -r src dst type ttl meta; do
  exists=$(run_redis EXISTS "${dst}")
  src_exists=$(run_redis EXISTS "${src}")
  new_type=$(run_redis TYPE "${dst}")
  new_ttl=$(run_redis TTL "${dst}")
  ok=1
  [ "${exists}" -eq 1 ] || ok=0
  [ "${src_exists}" -eq 0 ] || ok=0
  [ "${new_type}" = "${type}" ] || ok=0
  if [ "${type}" = "stream" ] && [ -n "${meta}" ]; then
    xlen=$(run_redis XLEN "${dst}")
    groups=$(run_redis XINFO GROUPS "${dst}" 2>/dev/null | tr '\n' '|')
    expected_xlen="${meta#XLEN=}"
    expected_xlen="${expected_xlen%%;*}"
    expected_groups="${meta#*GROUPS=}"
    [ "${xlen}" = "${expected_xlen}" ] || ok=0
    [ "${groups}" = "${expected_groups}" ] || ok=0
  else
    if [ "${ttl}" = "-1" ]; then
      [ "${new_ttl}" = "-1" ] || ok=0
    else
      # TTL keeps ticking between preflight and postflight; allow small drift.
      drift=$((ttl - new_ttl))
      [ "${drift}" -ge -2 ] && [ "${drift}" -le 10 ] || ok=0
    fi
  fi
  if [ "${ok}" -ne 1 ]; then
    echo "[postflight] FAIL category=$(category_of "${dst}") (type=${new_type} ttl=${new_ttl} srcExists=${src_exists})" >&2
    exit 1
  fi
  verified=$((verified + 1))
done < "${STATE}"

echo "[done] Stage3 -> Stage4 Redis cutover complete: renamed=${renamed} verified=${verified} deprecated-left=${deprecated}"
