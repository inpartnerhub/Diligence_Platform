#!/usr/bin/env bash
# Local Keycloak bootstrap for the "diligence" realm. Every credential comes from .env — nothing is hard-coded.
#   1. sets the diligence-admin-api client secret to KEYCLOAK_ADMIN_CLIENT_SECRET (used by the backend)
#   2. creates the local super admin (OTP is enforced on first login), a project manager and, when configured,
#      one end-to-end test user per role — each with the id the application already knows for its email, if any
#   3. optionally registers Microsoft Entra ID / Google SSO when their client ids are present in .env
set -euo pipefail
cd "$(dirname "$0")/../../.."
set -a; source .env; set +a

REALM=diligence
kc() { docker compose exec -T keycloak /opt/keycloak/bin/kcadm.sh "$@"; }

kc config credentials --server http://localhost:8180 --realm master \
  --user "$KEYCLOAK_ADMIN_USERNAME" --password "$KEYCLOAK_ADMIN_PASSWORD"

client_uuid=$(kc get clients -r "$REALM" -q clientId=diligence-admin-api --fields id --format csv --noquotes)
kc update "clients/$client_uuid" -r "$REALM" -s "secret=$KEYCLOAK_ADMIN_CLIENT_SECRET"
echo "Set diligence-admin-api client secret."

# Users are created with the id the application already has for their email (adm.user_account): Keycloak's id is the
# account id (token `sub`), so a recreated Keycloak keeps every project membership instead of minting new ids (#83).
psql() { docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"; }
existing_id() { # email → account id, empty when unknown or the database is not up
  psql -Atq -v email="$1" 2>/dev/null <<'SQL' || true
SELECT id FROM adm.user_account WHERE lower(email) = lower(:'email');
SQL
}

create_user() { # username email first last role [otp]; password in USER_PASSWORD, OTP secret in USER_OTP_SECRET
  if [ -n "$(kc get users -r "$REALM" -q "username=$1" --fields id --format csv --noquotes)" ]; then
    echo "User $1 already exists."; return
  fi
  local id
  id=$(existing_id "$2")
  USER_ID="$id" python3 -c '
import json, os, sys
user, email, first, last, role, otp = sys.argv[1:7]
credentials = [{"type": "password", "value": os.environ["USER_PASSWORD"], "temporary": False}]
if otp == "otp":
    credentials.append({"type": "otp", "userLabel": "e2e",
        "secretData": json.dumps({"value": os.environ["USER_OTP_SECRET"]}),
        "credentialData": json.dumps({"subType": "totp", "digits": 6, "period": 30, "algorithm": "HmacSHA1",
                                      "counter": 0})})
rep = {"username": user, "email": email, "firstName": first, "lastName": last, "enabled": True,
       "emailVerified": True, "credentials": credentials, "realmRoles": [role]}
if os.environ.get("USER_ID"):
    rep["id"] = os.environ["USER_ID"]
print(json.dumps({"ifResourceExists": "FAIL", "users": [rep]}))
' "$1" "$2" "$3" "$4" "$5" "${6:-}" | kc create partialImport -r "$REALM" -f - > /dev/null
  echo "Created $5 user '$1'${id:+ with its existing account id $id}."
}
USER_PASSWORD="$KEYCLOAK_DEV_ADMIN_PASSWORD" \
  create_user "$KEYCLOAK_DEV_ADMIN" "$KEYCLOAK_DEV_ADMIN@diligence.test" Local Admin super_admin
USER_PASSWORD="$KEYCLOAK_DEV_PM_PASSWORD" \
  create_user "$KEYCLOAK_DEV_PM" "$KEYCLOAK_DEV_PM@diligence.test" Local Manager project_manager

# End-to-end test users, one per role (tests/e2e). External roles require OTP, so they get a TOTP credential from a
# known secret and the tests compute the codes. Local only: set KEYCLOAK_E2E_PASSWORD and KEYCLOAK_E2E_TOTP_SECRET.
if [ -n "${KEYCLOAK_E2E_PASSWORD:-}" ] && [ -n "${KEYCLOAK_E2E_TOTP_SECRET:-}" ] \
  && [[ "$KEYCLOAK_E2E_PASSWORD$KEYCLOAK_E2E_TOTP_SECRET" != *change-me* ]]; then
  export USER_PASSWORD="$KEYCLOAK_E2E_PASSWORD" USER_OTP_SECRET="$KEYCLOAK_E2E_TOTP_SECRET"
  create_user e2e.partner e2e.partner@diligence.test E2E Partner partner
  create_user e2e.analyst e2e.analyst@diligence.test E2E Analyst analyst
  create_user e2e.target e2e.target@diligence.test E2E Target target otp
  create_user e2e.client e2e.client@diligence.test E2E Client client otp
  unset USER_PASSWORD USER_OTP_SECRET
fi

add_idp() { # alias providerId clientId clientSecret extra...
  local alias=$1 provider=$2 id=$3 secret=$4; shift 4
  if kc get "identity-provider/instances/$alias" -r "$REALM" > /dev/null 2>&1; then
    echo "Identity provider $alias already configured."; return
  fi
  kc create identity-provider/instances -r "$REALM" -s "alias=$alias" -s "providerId=$provider" -s enabled=true \
    -s 'config.syncMode=IMPORT' -s "config.clientId=$id" -s "config.clientSecret=$secret" "$@"
  echo "Configured SSO identity provider $alias."
}
if [ -n "${ENTRA_CLIENT_ID:-}" ]; then
  add_idp microsoft microsoft "$ENTRA_CLIENT_ID" "$ENTRA_CLIENT_SECRET" -s "config.tenant=$ENTRA_TENANT_ID"
fi
if [ -n "${GOOGLE_CLIENT_ID:-}" ]; then
  add_idp google google "$GOOGLE_CLIENT_ID" "$GOOGLE_CLIENT_SECRET" -s "config.hostedDomain=${GOOGLE_HOSTED_DOMAIN:-}"
fi
