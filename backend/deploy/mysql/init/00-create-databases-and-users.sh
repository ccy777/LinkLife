#!/usr/bin/env bash
# 018F MySQL first-boot initializer (runs inside the compose mysql container).
# - Reads the four service DB passwords from container environment variables.
# - Creates the four databases and four least-privilege service users.
# - Applies each service's own db/schema.sql (the single official fresh source).
# No real/default password is stored in this tracked file; passwords are never echoed.
set -euo pipefail

: "${LINKLIFE_IDENTITY_DB_PASSWORD:?LINKLIFE_IDENTITY_DB_PASSWORD required}"
: "${LINKLIFE_MERCHANT_DB_PASSWORD:?LINKLIFE_MERCHANT_DB_PASSWORD required}"
: "${LINKLIFE_TRANSACTION_DB_PASSWORD:?LINKLIFE_TRANSACTION_DB_PASSWORD required}"
: "${LINKLIFE_SOCIAL_DB_PASSWORD:?LINKLIFE_SOCIAL_DB_PASSWORD required}"
: "${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD required}"

run_root_mysql() {
  mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" "$@"
}

for db in linklife_identity linklife_merchant linklife_transaction linklife_social; do
  run_root_mysql -e "CREATE DATABASE IF NOT EXISTS \`${db}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
done

run_root_mysql -e "CREATE USER IF NOT EXISTS 'linklife_identity_user'@'%' IDENTIFIED BY '${LINKLIFE_IDENTITY_DB_PASSWORD}';"
run_root_mysql -e "CREATE USER IF NOT EXISTS 'linklife_merchant_user'@'%' IDENTIFIED BY '${LINKLIFE_MERCHANT_DB_PASSWORD}';"
run_root_mysql -e "CREATE USER IF NOT EXISTS 'linklife_transaction_user'@'%' IDENTIFIED BY '${LINKLIFE_TRANSACTION_DB_PASSWORD}';"
run_root_mysql -e "CREATE USER IF NOT EXISTS 'linklife_social_user'@'%' IDENTIFIED BY '${LINKLIFE_SOCIAL_DB_PASSWORD}';"

run_root_mysql -e "GRANT SELECT, INSERT, UPDATE, DELETE ON \`linklife_identity\`.* TO 'linklife_identity_user'@'%';"
run_root_mysql -e "GRANT SELECT, INSERT, UPDATE, DELETE ON \`linklife_merchant\`.* TO 'linklife_merchant_user'@'%';"
run_root_mysql -e "GRANT SELECT, INSERT, UPDATE, DELETE ON \`linklife_transaction\`.* TO 'linklife_transaction_user'@'%';"
run_root_mysql -e "GRANT SELECT, INSERT, UPDATE, DELETE ON \`linklife_social\`.* TO 'linklife_social_user'@'%';"
run_root_mysql -e "FLUSH PRIVILEGES;"

for db in linklife_identity linklife_merchant linklife_transaction linklife_social; do
  echo "[init] applying schema for ${db}"
  run_root_mysql --database="${db}" < "/opt/schemas/${db}.sql"
done

echo "[init] mysql databases/users/schemas ready"
