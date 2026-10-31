#!/usr/bin/env bash
# Local Keycloak bootstrap for the "diligence" realm. Every credential comes from .env — nothing is hard-coded.
#   1. sets the diligence-admin-api client secret to KEYCLOAK_ADMIN_CLIENT_SECRET (used by the backend)
#   2. creates the local super admin (OTP is enforced on first login), a project manager and, when configured,
#      one end-to-end test user per role
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

create_user() { # username email first last password role
  if [ -n "$(kc get users -r "$REALM" -q "username=$1" --fields id --format csv --noquotes)" ]; then
    echo "User $1 already exists."; return
  fi
  kc create users -r "$REALM" -s "username=$1" -s "email=$2" -s "firstName=$3" -s "lastName=$4" \
    -s enabled=true -s emailVerified=true
  kc set-password -r "$REALM" --username "$1" --new-password "$5"
  kc add-roles -r "$REALM" --uusername "$1" --rolename "$6"
  echo "Created $6 user '$1'."
}
create_user "$KEYCLOAK_DEV_ADMIN" "$KEYCLOAK_DEV_ADMIN@diligence.test" Local Admin "$KEYCLOAK_DEV_ADMIN_PASSWORD" super_admin
create_user "$KEYCLOAK_DEV_PM" "$KEYCLOAK_DEV_PM@diligence.test" Local Manager "$KEYCLOAK_DEV_PM_PASSWORD" project_manager

# End-to-end test users, one per role (tests/e2e). External roles require OTP, so they get a TOTP credential from a
# known secret and the tests compute the codes. Local only: set KEYCLOAK_E2E_PASSWORD and KEYCLOAK_E2E_TOTP_SECRET.
create_e2e_user() { # username role first last [otp]
  if [ -n "$(kc get users -r "$REALM" -q "username=$1" --fields id --format csv --noquotes)" ]; then
    echo "User $1 already exists."; return
  fi
  python3 -c '
import json, sys
user, first, last, otp = sys.argv[1:5]
credentials = [{"type": "password", "value": sys.argv[5], "temporary": False}]
if otp == "otp":
    credentials.append({"type": "otp", "userLabel": "e2e", "secretData": json.dumps({"value": sys.argv[6]}),
        "credentialData": json.dumps({"subType": "totp", "digits": 6, "period": 30, "algorithm": "HmacSHA1",
                                      "counter": 0})})
print(json.dumps({"username": user, "email": user + "@diligence.test", "firstName": first, "lastName": last,
                  "enabled": True, "emailVerified": True, "credentials": credentials}))
' "$1" "$3" "$4" "${5:-}" "$KEYCLOAK_E2E_PASSWORD" "$KEYCLOAK_E2E_TOTP_SECRET" | kc create users -r "$REALM" -f -
  kc add-roles -r "$REALM" --uusername "$1" --rolename "$2"
  echo "Created $2 e2e user '$1'."
}
if [ -n "${KEYCLOAK_E2E_PASSWORD:-}" ] && [ -n "${KEYCLOAK_E2E_TOTP_SECRET:-}" ] \
  && [[ "$KEYCLOAK_E2E_PASSWORD$KEYCLOAK_E2E_TOTP_SECRET" != *change-me* ]]; then
  create_e2e_user e2e.partner partner E2E Partner
  create_e2e_user e2e.analyst analyst E2E Analyst
  create_e2e_user e2e.target target E2E Target otp
  create_e2e_user e2e.client client E2E Client otp
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
