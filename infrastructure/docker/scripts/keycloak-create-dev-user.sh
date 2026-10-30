#!/usr/bin/env bash
# Creates a local developer account in the "platform" realm. Credentials come from .env — never hard-coded.
set -euo pipefail
cd "$(dirname "$0")/../../.."
set -a; source .env; set +a

kc() { docker compose exec -T keycloak /opt/keycloak/bin/kcadm.sh "$@"; }

kc config credentials --server http://localhost:8180 --realm master \
  --user "$KEYCLOAK_ADMIN_USERNAME" --password "$KEYCLOAK_ADMIN_PASSWORD"
kc create users -r platform -s username="$KEYCLOAK_DEV_USER" -s enabled=true -s emailVerified=true \
  -s email="$KEYCLOAK_DEV_USER@localhost.test" -s firstName=Dev -s lastName=User
kc set-password -r platform --username "$KEYCLOAK_DEV_USER" --new-password "$KEYCLOAK_DEV_USER_PASSWORD"
kc add-roles -r platform --uusername "$KEYCLOAK_DEV_USER" --rolename platform_user --rolename platform_admin --rolename platform_operator
echo "Created user '$KEYCLOAK_DEV_USER' in realm 'platform'."
