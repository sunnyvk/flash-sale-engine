#!/usr/bin/env bash
# Run from AWS CloudShell or your laptop (AWS CLI logged in). Stores the production configuration in SSM Parameter Store
# as SecureStrings under /flashsale/prod/. Secrets are generated here and never printed except the admin password.
#   DOMAIN=flash.example.com LETSENCRYPT_EMAIL=you@example.com GHCR_REPO=<github-user>/<repo> ./put-parameters.sh
# Values are hex/alphanumeric on purpose: compose env-files interpolate "$", so exotic characters would corrupt them.
set -euo pipefail
: "${DOMAIN:?set DOMAIN}" "${LETSENCRYPT_EMAIL:?set LETSENCRYPT_EMAIL}" "${GHCR_REPO:?set GHCR_REPO (lowercase owner/repo)}"
REGION=${AWS_REGION:-ap-south-1}
P=/flashsale/prod

put() { # name value type
  aws ssm put-parameter --region "$REGION" --name "$P/$1" --value "$2" --type "$3" --overwrite >/dev/null
}
gen() { openssl rand -hex "$1"; }

put DOMAIN "$DOMAIN" String
put LETSENCRYPT_EMAIL "$LETSENCRYPT_EMAIL" String
put GHCR_REPO "${GHCR_REPO,,}" String
put ADMIN_USERNAME admin String

# Only generate secrets that do not exist yet, so re-running never rotates the database passwords under a live volume
# (MySQL only reads them when the volume is first initialised).
for name in MYSQL_ROOT_PASSWORD BOOKING_DB_PASSWORD PAYMENT_DB_PASSWORD NOTIFICATION_DB_PASSWORD REDIS_PASSWORD; do
  aws ssm get-parameter --region "$REGION" --name "$P/$name" >/dev/null 2>&1 || put "$name" "$(gen 16)" SecureString
done
aws ssm get-parameter --region "$REGION" --name "$P/JWT_SECRET" >/dev/null 2>&1 || put JWT_SECRET "$(gen 32)" SecureString
if ! aws ssm get-parameter --region "$REGION" --name "$P/ADMIN_PASSWORD" >/dev/null 2>&1; then
  ADMIN_PW="Fs$(gen 12)"
  put ADMIN_PASSWORD "$ADMIN_PW" SecureString
  echo "Admin login created. username: admin   password: $ADMIN_PW   (shown once; also readable in SSM)"
fi
# Optional monitoring: OBSERVABILITY=1 ./put-parameters.sh  (Prometheus + Grafana on the instance, reachable only through
# an SSM port-forward, see README). Adds about 600 MB of RAM: fine on t3.large, too much for t3.medium.
if [ "${OBSERVABILITY:-0}" = 1 ]; then
  put OBSERVABILITY 1 String
  if ! aws ssm get-parameter --region "$REGION" --name "$P/GRAFANA_ADMIN_PASSWORD" >/dev/null 2>&1; then
    GRAFANA_PW="Gf$(gen 12)"
    put GRAFANA_ADMIN_PASSWORD "$GRAFANA_PW" SecureString
    echo "Grafana login created. username: admin   password: $GRAFANA_PW   (shown once; also readable in SSM)"
  fi
fi
echo "Parameters stored under $P in $REGION."
