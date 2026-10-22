#!/usr/bin/env bash
# Nightly from cron: dumps the three databases, compresses, streams to S3 (nothing is written to local disk).
# Restore:  aws s3 cp s3://BUCKET/mysql/FILE.sql.gz - | gunzip | docker exec -i fs-mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD"'
set -euo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=deploy/aws/lib.sh
. "$HERE/lib.sh"
require_root

BUCKET=$(aws ssm get-parameter --name "${PARAM_PATH}BACKUP_BUCKET" --query Parameter.Value --output text --region "$(aws_region)" 2>/dev/null || true)
[ -n "$BUCKET" ] || { log "no BACKUP_BUCKET parameter: backups are not configured"; exit 0; }

KEY="mysql/flashsale-$(date -u +%Y%m%dT%H%M%SZ).sql.gz"
log "dumping to s3://$BUCKET/$KEY"
docker exec fs-mysql sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --databases booking_db payment_db notification_db' \
  | gzip -9 \
  | aws s3 cp - "s3://$BUCKET/$KEY" --region "$(aws_region)" --sse AES256 --only-show-errors
log "backup done"
