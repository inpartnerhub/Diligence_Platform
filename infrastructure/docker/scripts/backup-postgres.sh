#!/usr/bin/env bash
# Daily PostgreSQL backup for compose.prod.yaml (application DB + Keycloak DB).
# Example cron (02:00 UTC daily):
#   0 2 * * * /opt/diligence/infrastructure/docker/scripts/backup-postgres.sh >> /var/log/diligence-backup.log 2>&1
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$ROOT_DIR"

ENV_FILE="${ENV_FILE:-.env.prod}"
COMPOSE_FILE="${COMPOSE_FILE:-compose.prod.yaml}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/diligence}"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-14}"

if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

mkdir -p "$BACKUP_DIR"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
POSTGRES_USER="${POSTGRES_USER:-diligence}"
POSTGRES_DB="${POSTGRES_DB:-diligence}"
KEYCLOAK_DB_NAME="${KEYCLOAK_DB_NAME:-keycloak}"

# Cluster-global roles (diligence_app, diligence, keycloak) are not part of per-database dumps. Capture them so a
# restore onto a fresh host can recreate the login/RLS roles before the data is loaded.
globals_out="$BACKUP_DIR/globals-${STAMP}.sql.gz"
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" exec -T postgres \
  pg_dumpall -U "$POSTGRES_USER" --roles-only | gzip -9 > "$globals_out"
echo "Created backup: $globals_out"
if [ -n "${BACKUP_S3_URI:-}" ]; then
  aws s3 cp "$globals_out" "${BACKUP_S3_URI%/}/globals-${STAMP}.sql.gz"
fi

for db in "$POSTGRES_DB" "$KEYCLOAK_DB_NAME"; do
  out="$BACKUP_DIR/${db}-${STAMP}.sql.gz"
  docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" exec -T postgres \
    pg_dump -U "$POSTGRES_USER" --no-owner --no-privileges "$db" | gzip -9 > "$out"
  echo "Created backup: $out"
  if [ -n "${BACKUP_S3_URI:-}" ]; then
    aws s3 cp "$out" "${BACKUP_S3_URI%/}/${db}-${STAMP}.sql.gz"
  fi
done

find "$BACKUP_DIR" -type f -name '*.sql.gz' -mtime +"$RETENTION_DAYS" -delete
