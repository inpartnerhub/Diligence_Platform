#!/usr/bin/env bash
# Seeds (or with "cleanup" removes) synthetic load-test data in the local stack: projects coded PERF-nnn in
# PostgreSQL, and their documents in the OpenSearch index so full-text search has something to chew on.
#
#   tests/performance/seed/seed.sh            # defaults below; override with SEED_* variables
#   tests/performance/seed/seed.sh cleanup
#
# The member is the user every seeded project is managed by (default: the local PM from `make keycloak-bootstrap`).
set -euo pipefail
cd "$(dirname "$0")/../../.."
set -a; source .env; set +a

MEMBER_EMAIL=${SEED_MEMBER_EMAIL:-${KEYCLOAK_DEV_PM}@diligence.test}
OPENSEARCH=${OPENSEARCH_URL:-http://localhost:9200}
INDEX=${OPENSEARCH_INDEX_PREFIX:-diligence}-documents
psql() { docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"; }

if [ "${1:-}" = cleanup ]; then
  ids=$(psql -Atc "select string_agg('\"' || id || '\"', ',') from prj.project where code like 'PERF-%'")
  if [ -n "$ids" ]; then
    curl -fsS -X POST "$OPENSEARCH/$INDEX/_delete_by_query?refresh=true" -H 'Content-Type: application/json' \
      -d "{\"query\":{\"terms\":{\"projectId\":[$ids]}}}" > /dev/null
  fi
  psql -q -f - < tests/performance/seed/cleanup.sql
  echo "Removed the PERF-* projects and their search entries."
  exit 0
fi

if ! curl -fs -o /dev/null "$OPENSEARCH/$INDEX"; then
  echo "Search index $INDEX does not exist yet: upload one document so the backend creates it." >&2
  exit 1
fi
MEMBER=$(psql -Atc "select id from adm.user_account where lower(email) = lower('$MEMBER_EMAIL')")
if [ -z "$MEMBER" ]; then
  echo "No account for $MEMBER_EMAIL: sign in once (or run make keycloak-bootstrap) first." >&2
  exit 1
fi

psql -q -v member="$MEMBER" -v projects="${SEED_PROJECTS:-20}" -v items="${SEED_ITEMS:-200}" \
  -v findings="${SEED_FINDINGS:-80}" -v questions="${SEED_QUESTIONS:-60}" \
  -v documents="${SEED_DOCUMENTS:-500}" -f - < tests/performance/seed/seed.sql

# Bulk-index the seeded documents with synthetic Indonesian/English due-diligence text.
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
psql -At > "$work/bulk.ndjson" <<SQL
SELECT json_build_object('index', json_build_object('_index', '$INDEX', '_id', d.id))::text || E'\n' ||
       json_build_object('documentId', d.id, 'versionId', d.current_version_id, 'projectId', d.project_id,
           'folderId', d.folder_id, 'name', d.name, 'contentType', 'application/pdf',
           'uploadedAt', to_char(d.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
           'content', c.content)::text
FROM dr.document d
         JOIN prj.project p ON p.id = d.project_id AND p.code LIKE 'PERF-%'
         CROSS JOIN LATERAL (
    SELECT string_agg(v.words[1 + floor(random() * array_length(v.words, 1))::int], ' ') AS content
    FROM (SELECT ARRAY ['akta', 'pendirian', 'perseroan', 'modal', 'saham', 'kontrak', 'perjanjian', 'laporan',
        'keuangan', 'pajak', 'audit', 'izin', 'usaha', 'karyawan', 'aset', 'utang', 'jaminan', 'sengketa', 'direksi',
        'komisaris', 'agreement', 'contract', 'liability', 'warranty', 'indemnity', 'revenue', 'tax', 'license',
        'employee', 'lease', 'shareholder', 'dividend', 'compliance', 'environmental', 'litigation'] AS words) AS v
             CROSS JOIN generate_series(1, 120) AS g
    WHERE d.id IS NOT NULL) AS c;
SQL
split -l 4000 "$work/bulk.ndjson" "$work/chunk-"
for chunk in "$work"/chunk-*; do
  curl -fsS -X POST "$OPENSEARCH/_bulk" -H 'Content-Type: application/x-ndjson' --data-binary "@$chunk" \
    | grep -q '"errors":false' || { echo "Bulk indexing reported errors ($chunk)" >&2; exit 1; }
done
curl -fsS -X POST "$OPENSEARCH/$INDEX/_refresh" > /dev/null

echo "Indexed $(($(wc -l < "$work/bulk.ndjson") / 2)) documents."
echo "PROJECT_ID for load.js (PERF-001): $(psql -Atc "select id from prj.project where code = 'PERF-001'")"
