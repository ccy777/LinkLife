#!/usr/bin/env sh
# 018F one-shot Stage3 -> Stage4 MySQL migration tool.
#
# Frozen preconditions (fail-closed):
#   - source schema must be the stage3-v1.1 structure (one legacy database);
#   - the four Stage4 target databases already ran their current fresh schema;
#   - application write traffic is stopped before running;
#   - run with an infrastructure/migration admin DB user, never a service user.
#
# Behavior:
#   - preflight: every target business table must be empty, OR the operator explicitly
#     confirms clearing only the known fresh-install seed rows
#     (LINKLIFE_MYSQL_CLEAR_FRESH_SEED=1). Any non-seed row aborts with 0 changes.
#   - migration: full-row INSERT ... SELECT per table, preserving primary keys and times;
#     no INSERT IGNORE, no REPLACE, no DROP of the legacy source.
#   - postflight: per-table source count == target count; auto_increment re-aligned.
set -eu

: "${LINKLIFE_MYSQL_ADMIN_HOST:?required}"
: "${LINKLIFE_MYSQL_ADMIN_PORT:?required}"
: "${LINKLIFE_MYSQL_ADMIN_USER:?required}"
: "${LINKLIFE_MYSQL_ADMIN_PASSWORD:?required}"
: "${LINKLIFE_MYSQL_SOURCE_DB:?required}"

LINKLIFE_IDENTITY_DB="${LINKLIFE_IDENTITY_DB:-linklife_identity}"
LINKLIFE_MERCHANT_DB="${LINKLIFE_MERCHANT_DB:-linklife_merchant}"
LINKLIFE_TRANSACTION_DB="${LINKLIFE_TRANSACTION_DB:-linklife_transaction}"
LINKLIFE_SOCIAL_DB="${LINKLIFE_SOCIAL_DB:-linklife_social}"
CLEAR_SEED="${LINKLIFE_MYSQL_CLEAR_FRESH_SEED:-0}"

run_mysql() {
  mysql -h "${LINKLIFE_MYSQL_ADMIN_HOST}" -P "${LINKLIFE_MYSQL_ADMIN_PORT}" \
    -u "${LINKLIFE_MYSQL_ADMIN_USER}" -p"${LINKLIFE_MYSQL_ADMIN_PASSWORD}" -N -B "$@"
}

# "target_db|table|seed_ids" (seed_ids empty means no fresh seed rows).
TABLES="
${LINKLIFE_IDENTITY_DB}|tb_user|1,2,4,5
${LINKLIFE_IDENTITY_DB}|tb_user_info|
${LINKLIFE_MERCHANT_DB}|tb_shop|1,2,3,4,5,6,7,8,9,10,11,12,13,14
${LINKLIFE_MERCHANT_DB}|tb_shop_type|1,2,3,4,5,6,7,8,9,10
${LINKLIFE_TRANSACTION_DB}|tb_voucher|1
${LINKLIFE_TRANSACTION_DB}|tb_seckill_voucher|
${LINKLIFE_TRANSACTION_DB}|tb_voucher_order|
${LINKLIFE_TRANSACTION_DB}|tb_order_status_log|
${LINKLIFE_TRANSACTION_DB}|tb_outbox_event|
${LINKLIFE_SOCIAL_DB}|tb_blog|4,5,6,7
${LINKLIFE_SOCIAL_DB}|tb_blog_comments|
${LINKLIFE_SOCIAL_DB}|tb_blog_like|
${LINKLIFE_SOCIAL_DB}|tb_follow|
"

CLEAR_FILE="/tmp/linklife-mysql-clear-$$.tmp"
TABLES_FILE="/tmp/linklife-mysql-tables-$$.tmp"
: > "${CLEAR_FILE}"
printf '%s\n' "${TABLES}" > "${TABLES_FILE}"
trap 'rm -f "${CLEAR_FILE}" "${TABLES_FILE}"' EXIT INT TERM

echo "[preflight] source db=${LINKLIFE_MYSQL_SOURCE_DB}"
while IFS='|' read -r db table seeds; do
  [ -n "${table}" ] || continue
  source_count=$(run_mysql -e "SELECT COUNT(*) FROM \`${LINKLIFE_MYSQL_SOURCE_DB}\`.\`${table}\`;")
  target_count=$(run_mysql -e "SELECT COUNT(*) FROM \`${db}\`.\`${table}\`;")
  if [ "${target_count}" -ne 0 ]; then
    if [ "${CLEAR_SEED}" = "1" ] && [ -n "${seeds}" ]; then
      non_seed=$(run_mysql -e "SELECT COUNT(*) FROM \`${db}\`.\`${table}\` WHERE \`id\` NOT IN (${seeds});")
      if [ "${non_seed}" -ne 0 ]; then
        echo "[preflight] FAIL ${db}.${table} contains ${non_seed} non-seed row(s); refusing to clear" >&2
        exit 1
      fi
      echo "${db}|${table}|${seeds}" >> "${CLEAR_FILE}"
    else
      echo "[preflight] FAIL ${db}.${table} has ${target_count} row(s); target must be empty (or set LINKLIFE_MYSQL_CLEAR_FRESH_SEED=1 for known seeds only)" >&2
      exit 1
    fi
  fi
  echo "[preflight] OK ${LINKLIFE_MYSQL_SOURCE_DB}.${table} -> ${db}.${table} (source=${source_count}, target=${target_count})"
done < "${TABLES_FILE}"

while IFS='|' read -r db table seeds; do
  [ -n "${table}" ] || continue
  echo "[seed-clear] removing fresh seed rows from ${db}.${table}"
  run_mysql -e "DELETE FROM \`${db}\`.\`${table}\` WHERE \`id\` IN (${seeds});"
  remaining=$(run_mysql -e "SELECT COUNT(*) FROM \`${db}\`.\`${table}\`;")
  if [ "${remaining}" -ne 0 ]; then
    echo "[seed-clear] FAIL ${db}.${table} still has ${remaining} rows" >&2
    exit 1
  fi
done < "${CLEAR_FILE}"

echo "[migrate] copying rows (full-row, preserving ids/times/status)"
while IFS='|' read -r db table seeds; do
  [ -n "${table}" ] || continue
  source_count=$(run_mysql -e "SELECT COUNT(*) FROM \`${LINKLIFE_MYSQL_SOURCE_DB}\`.\`${table}\`;")
  if [ "${source_count}" -eq 0 ]; then
    continue
  fi
  run_mysql -e "INSERT INTO \`${db}\`.\`${table}\` SELECT * FROM \`${LINKLIFE_MYSQL_SOURCE_DB}\`.\`${table}\`;"
done < "${TABLES_FILE}"

echo "[postflight] verifying counts and auto_increment"
while IFS='|' read -r db table seeds; do
  [ -n "${table}" ] || continue
  source_count=$(run_mysql -e "SELECT COUNT(*) FROM \`${LINKLIFE_MYSQL_SOURCE_DB}\`.\`${table}\`;")
  target_count=$(run_mysql -e "SELECT COUNT(*) FROM \`${db}\`.\`${table}\`;")
  if [ "${source_count}" -ne "${target_count}" ]; then
    echo "[postflight] FAIL count mismatch ${table}: source=${source_count} target=${target_count}" >&2
    exit 1
  fi
  echo "[postflight] OK ${db}.${table} source=${source_count} target=${target_count}"
  max_id=$(run_mysql -e "SELECT IFNULL(MAX(\`id\`), 0) + 1 FROM \`${db}\`.\`${table}\`;" 2>/dev/null || true)
  if [ -n "${max_id}" ] && [ "${max_id}" -gt 1 ] 2>/dev/null; then
    run_mysql -e "ALTER TABLE \`${db}\`.\`${table}\` AUTO_INCREMENT = ${max_id};" 2>/dev/null || true
  fi
done < "${TABLES_FILE}"

echo "[done] Stage3 -> Stage4 MySQL migration complete; legacy source untouched"
