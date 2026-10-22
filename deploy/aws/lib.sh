#!/usr/bin/env bash
# Shared helpers, sourced by the other scripts in this directory. Runs ON the EC2 instance, as root.
# shellcheck shell=bash

APP_DIR=/opt/flashsale
REPO_DIR=$APP_DIR/repo
ENV_FILE=$APP_DIR/.env
PARAM_PATH=${PARAM_PATH:-/flashsale/prod/}
# shellcheck disable=SC2034  # used by deploy.sh / renew-cert.sh
CERT_VOLUME=flash-sale_letsencrypt          # compose project "flash-sale" + volume "letsencrypt"

die() { echo "ERROR: $*" >&2; exit 1; }
log() { echo "[$(date -u +%H:%M:%S)] $*"; }
require_root() { [ "$(id -u)" -eq 0 ] || die "run as root (sudo)"; }

# docker compose with the three files and the generated .env. Relative paths inside the files resolve against deploy/.
compose() {
  docker compose --env-file "$ENV_FILE" \
    -f "$REPO_DIR/deploy/docker-compose.yml" \
    -f "$REPO_DIR/deploy/docker-compose.app.yml" \
    -f "$REPO_DIR/deploy/aws/docker-compose.prod.yml" "$@"
}

# Reads KEY from the generated .env (grep instead of "source": the file holds secrets and must never be executed).
env_get() { grep -E "^$1=" "$ENV_FILE" | head -1 | cut -d= -f2-; }

aws_region() {
  local token
  token=$(curl -fsS -X PUT http://169.254.169.254/latest/api/token -H 'X-aws-ec2-metadata-token-ttl-seconds: 60')
  curl -fsS -H "X-aws-ec2-metadata-token: $token" http://169.254.169.254/latest/meta-data/placement/region
}

# Builds $ENV_FILE from SSM Parameter Store (/flashsale/prod/*): every parameter becomes KEY=value.
# The file is created with mode 600 and replaced atomically. Nothing secret is ever stored in git, user-data or CI.
build_env() {
  local region tmp name value
  region=$(aws_region)
  tmp=$(mktemp "$APP_DIR/.env.XXXXXX")
  chmod 600 "$tmp"
  aws ssm get-parameters-by-path --path "$PARAM_PATH" --with-decryption --region "$region" \
      --query 'Parameters[].[Name,Value]' --output text |
    while IFS=$'\t' read -r name value; do
      echo "${name##*/}=$value"
    done > "$tmp"
  for key in DOMAIN LETSENCRYPT_EMAIL GHCR_REPO MYSQL_ROOT_PASSWORD BOOKING_DB_PASSWORD PAYMENT_DB_PASSWORD \
             NOTIFICATION_DB_PASSWORD REDIS_PASSWORD JWT_SECRET ADMIN_USERNAME ADMIN_PASSWORD; do
    grep -q "^$key=" "$tmp" || { rm -f "$tmp"; die "SSM parameter ${PARAM_PATH}${key} is missing (run put-parameters.sh)"; }
  done
  mv "$tmp" "$ENV_FILE"
}
