#!/usr/bin/env bash
# Demo mode (branch `demo`, local stack only): creates one Keycloak user per role for the role picker at /demo.
# All demo users share DEMO_PASSWORD and an OTP credential from DEMO_TOTP_SECRET (both generated into .env on the first
# run), so the frontend can sign in as them without a login form. Re-running keeps existing users and account ids.
#   make demo-setup
set -euo pipefail
cd "$(dirname "$0")/../../.."
set -a; source .env; set +a

# Generate the shared demo credentials once.
if [ -z "${DEMO_PASSWORD:-}" ] || [ -z "${DEMO_TOTP_SECRET:-}" ]; then
  password="Demo-$(openssl rand -hex 12)"
  secret=$(python3 -c 'import base64,os;print(base64.b32encode(os.urandom(20)).decode())')
  printf '\n# Demo mode (make demo-setup): shared credentials of the demo.* users; local only\nDEMO_MODE=true\nDEMO_PASSWORD=%s\nDEMO_TOTP_SECRET=%s\n' \
    "$password" "$secret" >> .env
  echo "Added DEMO_MODE, DEMO_PASSWORD and DEMO_TOTP_SECRET to .env (copy them to apps/frontend/.env.local)."
  set -a; source .env; set +a
fi

REALM=diligence
# KCADM / PSQL: run the tools directly instead of through compose (single-container demo image, deploy/huggingface).
kc() { if [ -n "${KCADM:-}" ]; then "$KCADM" "$@"; else docker compose exec -T keycloak /opt/keycloak/bin/kcadm.sh "$@"; fi; }
kc config credentials --server http://localhost:8180 --realm master \
  --user "$KEYCLOAK_ADMIN_USERNAME" --password "$KEYCLOAK_ADMIN_PASSWORD"

psql() { if [ -n "${PSQL:-}" ]; then "$PSQL" -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"; else docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"; fi; }
existing_id() { # email → account id, empty when unknown
  psql -Atq -v email="$1" 2>/dev/null <<'SQL' || true
SELECT id FROM adm.user_account WHERE lower(email) = lower(:'email');
SQL
}

# Every application role requires OTP (#161: composite of mfa_required). Realms imported before #161 lack it for some
# roles; align them with diligence-realm.json, otherwise demo sign-ins carry no "otp" in amr and step-up fails.
for role in super_admin partner project_manager analyst client target observer; do
  if ! kc get "roles/$role/composites" -r "$REALM" --fields name --format csv --noquotes | grep -qx mfa_required; then
    kc add-roles -r "$REALM" --rname "$role" --rolename mfa_required > /dev/null
    echo "Role $role now requires OTP (as in diligence-realm.json)."
  fi
done

# Step-up (#160) needs "amr" with "otp" in the tokens: the amr mapper on diligence-web and reference values on the
# password and OTP forms, as in diligence-realm.json. Realms imported before #160 lack them.
web_uuid=$(kc get clients -r "$REALM" -q clientId=diligence-web --fields id --format csv --noquotes)
if ! kc get "clients/$web_uuid/protocol-mappers/models" -r "$REALM" --fields protocolMapper --format csv --noquotes \
    | grep -qx oidc-amr-mapper; then
  kc create "clients/$web_uuid/protocol-mappers/models" -r "$REALM" -s name=amr -s protocol=openid-connect \
    -s protocolMapper=oidc-amr-mapper -s 'config."access.token.claim"=true' -s 'config."id.token.claim"=true' \
    -s 'config."introspection.token.claim"=true' > /dev/null
  echo "Added the amr mapper to diligence-web (#160)."
fi
reference() { # flow authenticator alias value
  local flow=$1 authenticator=$2 alias=$3 value=$4 line id config
  line=$(kc get "authentication/flows/${flow// /%20}/executions" -r "$REALM" \
    | python3 -c 'import json,sys; a=sys.argv[1]
for e in json.load(sys.stdin):
    if e.get("providerId")==a: print(e["id"], e.get("authenticationConfig",""))' "$authenticator")
  read -r id config <<<"$line"
  if [ -n "$id" ] && [ -z "$config" ]; then
    kc create "authentication/executions/$id/config" -r "$REALM" -s "alias=$alias" \
      -s "config.\"default.reference.value\"=$value" > /dev/null
    echo "Added amr reference \"$value\" to $authenticator in \"$flow\" (#160)."
  fi
}
reference "browser-mfa forms" auth-username-password-form amr-pwd pwd
reference "browser-mfa conditional otp" auth-otp-form amr-otp-browser-mfa otp

# username first last realm-role
USERS=(
  "demo.admin Demo SuperAdmin super_admin"
  "demo.partner Demo Partner partner"
  "demo.pm Demo ProjectManager project_manager"
  "demo.analyst Demo Analis analyst"
  "demo.observer Demo Auditor observer"
  "demo.client Demo Klien client"
  "demo.target Demo Target target"
)

for entry in "${USERS[@]}"; do
  read -r username first last role <<<"$entry"
  email="$username@diligence.test"
  kc_id=$(kc get users -r "$REALM" -q "username=$username" --fields id --format csv --noquotes)
  if [ -n "$kc_id" ]; then
    otp=$(kc get "users/$kc_id/credentials" -r "$REALM" --fields type --format csv --noquotes | grep -c '^otp$' || true)
    if [ "$otp" -gt 0 ]; then
      echo "User $username already exists."
      continue
    fi
    kc delete "users/$kc_id" -r "$REALM" # recreated below with the demo OTP credential and the same id
  fi
  id=$(existing_id "$email")
  USER_ID="${id:-$kc_id}" python3 -c '
import json, os, sys
user, email, first, last, role = sys.argv[1:6]
rep = {"username": user, "email": email, "firstName": first, "lastName": last, "enabled": True,
       "emailVerified": True, "realmRoles": [role],
       "credentials": [
           {"type": "password", "value": os.environ["DEMO_PASSWORD"], "temporary": False},
           {"type": "otp", "userLabel": "demo",
            "secretData": json.dumps({"value": os.environ["DEMO_TOTP_SECRET"]}),
            "credentialData": json.dumps({"subType": "totp", "digits": 6, "period": 30, "algorithm": "HmacSHA1",
                                          "counter": 0})}]}
if os.environ.get("USER_ID"):
    rep["id"] = os.environ["USER_ID"]
print(json.dumps({"ifResourceExists": "FAIL", "users": [rep]}))
' "$username" "$email" "$first" "$last" "$role" | kc create partialImport -r "$REALM" -f - > /dev/null
  echo "Created $role user '$username'."
done
echo "Demo users ready. Next: make demo-seed (demo project DEMO-001)."
