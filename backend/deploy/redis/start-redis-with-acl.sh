#!/usr/bin/env sh
# 018F Redis 7 ACL bootstrap (runs as the redis container entrypoint).
# Generates a temporary ACL file inside the container from environment variables,
# then starts redis-server with that ACL file. Passwords are never echoed and
# are injected only via container environment variables (dev/test runtime).
set -eu

: "${REDIS_ADMIN_PASSWORD:?REDIS_ADMIN_PASSWORD required}"
: "${REDIS_IDENTITY_PASSWORD:?REDIS_IDENTITY_PASSWORD required}"
: "${REDIS_MERCHANT_PASSWORD:?REDIS_MERCHANT_PASSWORD required}"
: "${REDIS_TRANSACTION_PASSWORD:?REDIS_TRANSACTION_PASSWORD required}"
: "${REDIS_SOCIAL_PASSWORD:?REDIS_SOCIAL_PASSWORD required}"
: "${REDIS_GATEWAY_PASSWORD:?REDIS_GATEWAY_PASSWORD required}"

ACL_FILE="${LINKLIFE_REDIS_ACL_FILE:-/tmp/redis-acl.conf}"
umask 077

cat > "${ACL_FILE}" <<EOF
user default on >${REDIS_ADMIN_PASSWORD} ~* &* +@all -@admin -@dangerous +info
user linklife_identity on >${REDIS_IDENTITY_PASSWORD} ~identity:* ~login:token:* ~redisson_lock__channel:{identity:*} &* +@all -@admin -@dangerous +info
user linklife_merchant on >${REDIS_MERCHANT_PASSWORD} ~merchant:* &* +@all -@admin -@dangerous +info
user linklife_transaction on >${REDIS_TRANSACTION_PASSWORD} ~transaction:* ~redisson_lock__channel:{transaction:*} &* +@all -@admin -@dangerous +info
user linklife_social on >${REDIS_SOCIAL_PASSWORD} ~social:* ~redisson_lock__channel:{social:*} &* +@all -@admin -@dangerous +info
user linklife_gateway on >${REDIS_GATEWAY_PASSWORD} ~identity:login:token:* ~login:token:* &* +@all -@admin -@dangerous +info
EOF

chmod 600 "${ACL_FILE}"

# Start redis-server with the ACL file (replaces this shell as PID 1).
# AOF is persisted under /data (Compose named volume) so container recreation
# keeps sessions/streams/submission state; appendfsync everysec bounds the
# crash-loss window to roughly one second (not absolute zero-loss).
exec redis-server --aclfile "${ACL_FILE}" --dir /data --appendonly yes --appendfsync everysec
