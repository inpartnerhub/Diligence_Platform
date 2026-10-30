#!/usr/bin/env bash
# Local Keycloak bootstrap for the "diligence" realm. Every credential comes from .env — nothing is hard-coded.
#   1. sets the diligence-admin-api client secret to KEYCLOAK_ADMIN_CLIENT_SECRET (used by the backend)
#   2. creates the local super admin (OTP is enforced on first login) and a project manager for e2e tests
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
