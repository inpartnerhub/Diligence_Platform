#!/usr/bin/env bash
# Seeds (or with "cleanup" removes) synthetic load-test data: projects coded PERF-nnn in PostgreSQL, and their
# documents in the OpenSearch index so full-text search has something to chew on.
#
#   tests/performance/seed/seed.sh              # local stack, defaults below; override with SEED_* variables
#   SEED_SCALE=prd tests/performance/seed/seed.sh   # PRD Bab 7 scale: 500,000 documents (see README.md)
#   tests/performance/seed/seed.sh cleanup
#
# Locally it talks to the compose services. Against a deployed environment set SEED_DATABASE_URL (a libpq connection
# URI for the database owner; needs psql on the PATH), OPENSEARCH_URL and, for a secured domain, OPENSEARCH_USERNAME
# and OPENSEARCH_PASSWORD. Both usually sit inside the VPC: run it from the bastion or through port forwards.
#
# The member is the user every seeded project is managed by (default: the local PM from `make keycloak-bootstrap`).
set -euo pipefail
cd "$(dirname "$0")/../../.."
if [ -f .env ]; then set -a; source .env; set +a; fi

case "${SEED_SCALE:-local}" in
  # 20 projects × 500 documents = 10,000; the default for a laptop.
  local) projects=${SEED_PROJECTS:-20} documents=${SEED_DOCUMENTS:-500} first=${SEED_DOCUMENTS_FIRST:-} ;;
  # 50 projects, 500,000 documents in all. PERF-001 holds 50,000 of them: the large data room the load test searches
  # and browses; the other 49 hold 9,183 each, plus 33 extra in PERF-002 to reach the total exactly.
  prd) projects=${SEED_PROJECTS:-50} documents=${SEED_DOCUMENTS:-9183} first=${SEED_DOCUMENTS_FIRST:-50000} ;;
  *) echo "Unknown SEED_SCALE ${SEED_SCALE}; use local or prd" >&2; exit 1 ;;
esac
first=${first:-$documents}

MEMBER_EMAIL=${SEED_MEMBER_EMAIL:-${KEYCLOAK_DEV_PM:-}@diligence.test}
OPENSEARCH=${OPENSEARCH_URL:-http://localhost:9200}
INDEX=${OPENSEARCH_INDEX_PREFIX:-diligence}-documents
search_auth=()
if [ -n "${OPENSEARCH_USERNAME:-}" ]; then search_auth=(-u "$OPENSEARCH_USERNAME:${OPENSEARCH_PASSWORD:-}"); fi
search() { curl -fsS ${search_auth[@]+"${search_auth[@]}"} "$@"; }

if [ -n "${SEED_DATABASE_URL:-}" ]; then
  psql() { command psql "$SEED_DATABASE_URL" "$@"; }
else
  psql() { docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"; }
fi

perf_ids() { psql -Atc "select string_agg('\"' || id || '\"', ',') from prj.project where code like 'PERF-%'"; }

if [ "${1:-}" = cleanup ]; then
  ids=$(perf_ids)
  if [ -n "$ids" ]; then
    search -X POST "$OPENSEARCH/$INDEX/_delete_by_query?refresh=true&conflicts=proceed&slices=auto" \
      -H 'Content-Type: application/json' -d "{\"query\":{\"terms\":{\"projectId\":[$ids]}}}" > /dev/null
  fi
  psql -q -f - < tests/performance/seed/cleanup.sql
  echo "Removed the PERF-* projects and their search entries."
  if [ -n "$ids" ]; then
    # Files uploaded by upload.js (and their renditions) stay in object storage; the database rows are gone.
    echo "Delete their stored files in the document bucket under these prefixes:"
    echo "$ids" | tr -d '"' | tr ',' '\n' | sed 's|^|  projects/|; s|$|/|'
  fi
  exit 0
fi

if ! search -o /dev/null "$OPENSEARCH/$INDEX"; then
  echo "Search index $INDEX does not exist yet: upload one document so the backend creates it." >&2
  exit 1
fi
if [ -n "$(perf_ids)" ]; then
  echo "PERF-* projects already exist: run '$0 cleanup' first." >&2
  exit 1
fi
MEMBER=$(psql -Atc "select id from adm.user_account where lower(email) = lower('$MEMBER_EMAIL')")
if [ -z "$MEMBER" ]; then
  echo "No account for $MEMBER_EMAIL: sign in once (or run make keycloak-bootstrap) first, or set SEED_MEMBER_EMAIL." >&2
  exit 1
fi

extra=0
if [ "${SEED_SCALE:-local}" = prd ] && [ -z "${SEED_DOCUMENTS:-}${SEED_DOCUMENTS_FIRST:-}${SEED_PROJECTS:-}" ]; then
  extra=$((500000 - first - (projects - 1) * documents))
fi
echo "Seeding $projects projects: PERF-001 with $first documents, the others with $documents each (+$extra in PERF-002)."
psql -q -v member="$MEMBER" -v projects="$projects" -v items="${SEED_ITEMS:-200}" \
  -v findings="${SEED_FINDINGS:-80}" -v questions="${SEED_QUESTIONS:-60}" \
  -v documents="$documents" -v first_documents="$first" -v extra_documents="$extra" \
  -f - < tests/performance/seed/seed.sql

# Bulk-index the seeded documents with synthetic Indonesian/English due-diligence text, one project at a time so the
# intermediate files stay small at PRD scale. Refreshing is switched off while loading and restored afterwards.
work=$(mktemp -d)
refresh=$(search "$OPENSEARCH/$INDEX/_settings/index.refresh_interval?flat_settings=true" \
  | sed -nE 's/.*"index\.refresh_interval":"([^"]+)".*/\1/p')
restore_value=null
if [ -n "$refresh" ]; then restore_value="\"$refresh\""; fi
restore() {
  search -X PUT "$OPENSEARCH/$INDEX/_settings" -H 'Content-Type: application/json' \
    -d "{\"index\":{\"refresh_interval\":$restore_value}}" > /dev/null || true
  rm -rf "$work"
}
trap restore EXIT
search -X PUT "$OPENSEARCH/$INDEX/_settings" -H 'Content-Type: application/json' \
  -d '{"index":{"refresh_interval":"-1"}}' > /dev/null

total=0
for project in $(psql -Atc "select id from prj.project where code like 'PERF-%' order by code"); do
  psql -At -v project="$project" -v index="$INDEX" -f - > "$work/bulk.ndjson" <<'SQL'
SELECT json_build_object('index', json_build_object('_index', :'index', '_id', d.id))::text || E'\n' ||
       json_build_object('documentId', d.id, 'versionId', d.current_version_id, 'projectId', d.project_id,
           'folderId', d.folder_id, 'name', d.name, 'contentType', 'application/pdf',
           'uploadedAt', to_char(d.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
           'content', c.content)::text
FROM dr.document d
         CROSS JOIN LATERAL (
    SELECT string_agg(v.words[1 + floor(random() * array_length(v.words, 1))::int], ' ') AS content
    FROM (SELECT ARRAY ['akta', 'pendirian', 'perseroan', 'modal', 'saham', 'kontrak', 'perjanjian', 'laporan',
        'keuangan', 'pajak', 'audit', 'izin', 'usaha', 'karyawan', 'aset', 'utang', 'jaminan', 'sengketa', 'direksi',
        'komisaris', 'agreement', 'contract', 'liability', 'warranty', 'indemnity', 'revenue', 'tax', 'license',
        'employee', 'lease', 'shareholder', 'dividend', 'compliance', 'environmental', 'litigation'] AS words) AS v
             CROSS JOIN generate_series(1, 120) AS g
    WHERE d.id IS NOT NULL) AS c
WHERE d.project_id = :'project';
SQL
  rm -f "$work"/chunk-*
  split -l 4000 "$work/bulk.ndjson" "$work/chunk-"
  for chunk in "$work"/chunk-*; do
    search -X POST "$OPENSEARCH/_bulk" -H 'Content-Type: application/x-ndjson' --data-binary "@$chunk" \
      | grep -q '"errors":false' || { echo "Bulk indexing reported errors ($chunk)" >&2; exit 1; }
  done
  total=$((total + $(wc -l < "$work/bulk.ndjson") / 2))
  echo "  indexed $total documents"
done
restore
trap - EXIT
search -X POST "$OPENSEARCH/$INDEX/_refresh" > /dev/null

echo "Indexed $total documents."
echo "PROJECT_ID for load.js (PERF-001): $(psql -Atc "select id from prj.project where code = 'PERF-001'")"
