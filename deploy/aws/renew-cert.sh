#!/usr/bin/env bash
# Daily from cron. Renews only when the certificate has less than 30 days left, so nginx is stopped for a few seconds
# about once every two months (standalone mode needs port 80). The trap guarantees nginx is started again.
set -euo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=deploy/aws/lib.sh
. "$HERE/lib.sh"
require_root

[ -f "$ENV_FILE" ] || die "$ENV_FILE not found (deploy once first)"
DOMAIN=$(env_get DOMAIN)
CERT_DIR=$(docker volume inspect -f '{{.Mountpoint}}' "$CERT_VOLUME")
CERT="$CERT_DIR/live/$DOMAIN/fullchain.pem"

if openssl x509 -checkend $((30 * 86400)) -noout -in "$CERT" >/dev/null; then
  log "certificate valid for more than 30 days: nothing to do"
  exit 0
fi

log "certificate expires within 30 days: renewing"
trap 'compose start nginx' EXIT
compose stop nginx
compose run --rm -p 80:80 certbot renew --standalone --non-interactive
log "renewed"
