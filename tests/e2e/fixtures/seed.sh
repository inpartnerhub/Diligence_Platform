#!/usr/bin/env bash
# Recreates project E2E-001 for the end-to-end suite on the local stack: every e2e user (make keycloak-bootstrap
# with KEYCLOAK_E2E_* set) is a member in their role, the target may upload into the Legal folder, and one request
# item waits to be released. Their account rows carry the demo client / target organizations.
#
#   tests/e2e/fixtures/seed.sh            # prints the project id
#   tests/e2e/fixtures/seed.sh cleanup
set -euo pipefail
cd "$(dirname "$0")/../../.."
set -a; source .env; set +a

KC=${KEYCLOAK_URL:-http://localhost:8180}
psql() { docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 "$@"; }

cleanup() {
  psql -q <<'SQL'
BEGIN;
CREATE TEMP TABLE e2e_project ON COMMIT DROP AS SELECT id FROM prj.project WHERE code = 'E2E-001';
DELETE FROM rpt.report_finding WHERE report_id IN (SELECT id FROM rpt.report WHERE project_id IN (SELECT id FROM e2e_project));
DELETE FROM rpt.report WHERE project_id IN (SELECT id FROM e2e_project);
DELETE FROM qna.question WHERE project_id IN (SELECT id FROM e2e_project);
DELETE FROM fnd.finding WHERE project_id IN (SELECT id FROM e2e_project);
DELETE FROM req.request_item WHERE project_id IN (SELECT id FROM e2e_project);
DELETE FROM dr.document_version WHERE project_id IN (SELECT id FROM e2e_project);
DELETE FROM dr.document WHERE project_id IN (SELECT id FROM e2e_project);
DELETE FROM dr.folder WHERE project_id IN (SELECT id FROM e2e_project);
DELETE FROM prj.project WHERE id IN (SELECT id FROM e2e_project);
COMMIT;
SQL
}

if [ "${1:-}" = cleanup ]; then
  cleanup
  echo "Removed project E2E-001."
  exit 0
fi

token=$(curl -fsS -d client_id=admin-cli -d username="$KEYCLOAK_ADMIN_USERNAME" \
  --data-urlencode password="$KEYCLOAK_ADMIN_PASSWORD" -d grant_type=password \
  "$KC/realms/master/protocol/openid-connect/token" | python3 -c 'import json,sys;print(json.load(sys.stdin)["access_token"])')
user_id() {
  curl -fsS -H "Authorization: Bearer $token" "$KC/admin/realms/diligence/users?username=$1&exact=true" \
    | python3 -c 'import json,sys;u=json.load(sys.stdin);print(u[0]["id"] if u else "")'
}
PM=$(user_id "$KEYCLOAK_DEV_PM"); PARTNER=$(user_id e2e.partner); ANALYST=$(user_id e2e.analyst)
TARGET=$(user_id e2e.target); CLIENT=$(user_id e2e.client)
for id in "$PM" "$PARTNER" "$ANALYST" "$TARGET" "$CLIENT"; do
  if [ -z "$id" ]; then
    echo "Missing e2e users: set KEYCLOAK_E2E_PASSWORD / KEYCLOAK_E2E_TOTP_SECRET and run make keycloak-bootstrap." >&2
    exit 1
  fi
done

cleanup
psql -q -At -v pm="$PM" -v partner="$PARTNER" -v analyst="$ANALYST" -v target="$TARGET" -v client="$CLIENT" <<'SQL'
BEGIN;
-- Organizations: the demo client and target if present, otherwise e2e ones.
INSERT INTO adm.organization (id, name, type)
SELECT gen_random_uuid(), 'E2E Client', 'CLIENT' WHERE NOT EXISTS (SELECT 1 FROM adm.organization WHERE type = 'CLIENT');
INSERT INTO adm.organization (id, name, type)
SELECT gen_random_uuid(), 'E2E Target', 'TARGET' WHERE NOT EXISTS (SELECT 1 FROM adm.organization WHERE type = 'TARGET');
CREATE TEMP TABLE e2e_org ON COMMIT DROP AS
SELECT (SELECT id FROM adm.organization WHERE type = 'CLIENT' ORDER BY created_at LIMIT 1) AS client_org,
       (SELECT id FROM adm.organization WHERE type = 'TARGET' ORDER BY created_at LIMIT 1) AS target_org;

-- Account mirror rows, as just-in-time provisioning would create them, with the external users' organizations.
INSERT INTO adm.user_account (id, organization_id, email, display_name, global_role, status)
VALUES (:'partner', NULL, 'e2e.partner@diligence.test', 'E2E Partner', 'PARTNER', 'ACTIVE'),
       (:'analyst', NULL, 'e2e.analyst@diligence.test', 'E2E Analyst', 'ANALYST', 'ACTIVE'),
       (:'target', (SELECT target_org FROM e2e_org), 'e2e.target@diligence.test', 'E2E Target', 'TARGET', 'ACTIVE'),
       (:'client', (SELECT client_org FROM e2e_org), 'e2e.client@diligence.test', 'E2E Client', 'CLIENT', 'ACTIVE')
ON CONFLICT (id) DO UPDATE SET organization_id = excluded.organization_id, status = 'ACTIVE';

CREATE TEMP TABLE e2e_ids ON COMMIT DROP AS
SELECT gen_random_uuid() AS project, gen_random_uuid() AS legal, gen_random_uuid() AS folder;
INSERT INTO prj.project (id, code, name, client_organization_id, target_organization_id, created_by)
SELECT project, 'E2E-001', 'End-to-end acquisition', (SELECT client_org FROM e2e_org), (SELECT target_org FROM e2e_org),
       :'pm'
FROM e2e_ids;
INSERT INTO prj.workstream (id, project_id, type) SELECT legal, project, 'LEGAL' FROM e2e_ids;
INSERT INTO prj.project_member (id, project_id, user_id, project_role)
SELECT gen_random_uuid(), project, m.user_id::uuid, m.role
FROM e2e_ids CROSS JOIN (VALUES (:'pm', 'PROJECT_MANAGER'), (:'partner', 'PARTNER'), (:'analyst', 'ANALYST'),
                                (:'target', 'TARGET'), (:'client', 'CLIENT')) AS m(user_id, role);

INSERT INTO dr.folder (id, project_id, name, workstream_id) SELECT folder, project, '01 Legal', legal FROM e2e_ids;
INSERT INTO dr.folder_access (id, project_id, folder_id, principal_type, project_role, permission)
SELECT gen_random_uuid(), project, folder, 'ROLE', 'TARGET', 'UPLOAD' FROM e2e_ids;

INSERT INTO req.request_item (id, project_id, workstream_id, number, category, description, priority, due_date)
SELECT gen_random_uuid(), project, legal, 'LEG-001', 'Corporate', 'Deed of establishment and all amendments',
       'HIGH', current_date + 14
FROM e2e_ids;

SELECT project FROM e2e_ids;
COMMIT;
SQL
