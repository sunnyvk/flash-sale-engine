#!/bin/bash
# EC2 user-data: runs ONCE as root at first boot (Amazon Linux 2023). provision.sh replaces __REPO_URL__.
# Contains no secrets: configuration is read from SSM Parameter Store at deploy time.
set -euxo pipefail

dnf -y install docker git jq cronie
systemctl enable --now docker crond

# Compose v2 plugin, pinned. (>= 2.24 is needed for the !reset / !override tags in the prod overlay.)
COMPOSE_VERSION=v2.39.1
mkdir -p /usr/local/lib/docker/cli-plugins
curl -fsSL "https://github.com/docker/compose/releases/download/${COMPOSE_VERSION}/docker-compose-linux-x86_64" \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

mkdir -p /opt/flashsale
git clone --depth 1 "__REPO_URL__" /opt/flashsale/repo
chmod +x /opt/flashsale/repo/deploy/aws/*.sh

cat > /etc/cron.d/flashsale <<'CRON'
17 3 * * * root /opt/flashsale/repo/deploy/aws/renew-cert.sh  >> /var/log/flashsale-renew.log 2>&1
30 2 * * * root /opt/flashsale/repo/deploy/aws/backup-mysql.sh >> /var/log/flashsale-backup.log 2>&1
CRON
chmod 644 /etc/cron.d/flashsale

echo "bootstrap finished" > /var/log/flashsale-bootstrap.done
