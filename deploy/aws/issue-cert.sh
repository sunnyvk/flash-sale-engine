#!/usr/bin/env bash
# Gets the first Let's Encrypt certificate (standalone mode: certbot answers the challenge itself on port 80, so nginx
# must not be running). Run once before the first deploy.   STAGING=1 uses Let's Encrypt's test CA (no rate limits,
# browsers will not trust it): do a staging run first to prove DNS and the security group are right.
set -euo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=deploy/aws/lib.sh
. "$HERE/lib.sh"
require_root

build_env
DOMAIN=$(env_get DOMAIN)
EMAIL=$(env_get LETSENCRYPT_EMAIL)

compose stop nginx >/dev/null 2>&1 || true
log "requesting a certificate for $DOMAIN${STAGING:+ (STAGING)}"
args=(certonly --standalone --non-interactive --agree-tos --no-eff-email -m "$EMAIL" -d "$DOMAIN")
[ -n "${STAGING:-}" ] && args+=(--staging --force-renewal)
compose run --rm -p 80:80 certbot "${args[@]}"
log "certificate stored. Now deploy: sudo $HERE/deploy.sh main"
