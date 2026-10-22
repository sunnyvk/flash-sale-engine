#!/usr/bin/env bash
# Deploys one commit of the app. Runs ON the instance as root (SSM Session Manager, or CI through SSM Run Command).
#   sudo /opt/flashsale/repo/deploy/aws/deploy.sh <40-char commit sha | branch>      # default: main
# Pulls the images CI built for that exact commit, so compose files and images always match.
set -euo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=deploy/aws/lib.sh
. "$HERE/lib.sh"
require_root

REF=${1:-main}

# Self-update: move the repo to the requested commit, then re-run the NEW copy of this script.
if [ -z "${DEPLOY_REEXEC:-}" ]; then
  log "checking out $REF"
  git -C "$REPO_DIR" fetch --quiet origin "$REF"
  git -C "$REPO_DIR" checkout --quiet --force --detach FETCH_HEAD
  DEPLOY_REEXEC=1 exec "$REPO_DIR/deploy/aws/deploy.sh" "$@"
fi

exec 9>/var/lock/flashsale-deploy.lock
flock -n 9 || die "another deploy is running"

log "reading configuration from SSM Parameter Store"
build_env
if [[ "$REF" =~ ^[0-9a-f]{40}$ ]]; then IMAGE_TAG="sha-$REF"; else IMAGE_TAG=latest; fi
grep -v '^IMAGE_TAG=' "$ENV_FILE" > "$ENV_FILE.new" || true
echo "IMAGE_TAG=$IMAGE_TAG" >> "$ENV_FILE.new"
chmod 600 "$ENV_FILE.new" && mv "$ENV_FILE.new" "$ENV_FILE"

DOMAIN=$(env_get DOMAIN)
CERT_DIR=$(docker volume inspect -f '{{.Mountpoint}}' "$CERT_VOLUME" 2>/dev/null || true)
[ -n "$CERT_DIR" ] && [ -f "$CERT_DIR/live/$DOMAIN/fullchain.pem" ] \
  || die "no TLS certificate for $DOMAIN yet. Run: sudo $HERE/issue-cert.sh"

log "pulling images ($IMAGE_TAG)"
compose pull
log "starting the stack"
compose up -d --wait --remove-orphans
docker image prune -f >/dev/null

log "checking https://$DOMAIN/healthz"
for _ in $(seq 1 30); do
  if curl -fsS "https://$DOMAIN/healthz" | grep -q UP; then log "DEPLOYED $IMAGE_TAG"; exit 0; fi
  sleep 2
done
compose ps
die "the service did not answer over HTTPS in time"
