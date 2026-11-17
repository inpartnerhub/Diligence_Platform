#!/usr/bin/env bash
# Runs once per container start (supervisord program "setup"), when the services are up: object storage bucket,
# Keycloak clients and demo users, then the demo project DEMO-001, all with the repository's own scripts.
set -euo pipefail
set -a; source /data/.env; set +a
cd /app

wait_http() { # url label
  echo "setup: waiting for $2…"
  until curl -fsS -o /dev/null --max-time 5 "$1"; do sleep 5; done
}
wait_http http://127.0.0.1:9333/cluster/status "object storage"
wait_http http://localhost:8180/realms/diligence/.well-known/openid-configuration Keycloak
wait_http http://127.0.0.1:8081/actuator/health/readiness backend

echo "setup: bucket $S3_BUCKET"
echo "s3.bucket.create -name $S3_BUCKET" | weed shell -master=127.0.0.1:9333 > /dev/null 2>&1 || true

export KCADM=/opt/keycloak/bin/kcadm.sh PSQL=psql PGHOST=127.0.0.1 PGPASSWORD="$POSTGRES_PASSWORD"
infrastructure/docker/scripts/keycloak-bootstrap.sh

# The web app's public address (the Space URL) as a redirect URI of diligence-web, next to the local ones.
"$KCADM" config credentials --server http://localhost:8180 --realm master \
  --user "$KEYCLOAK_ADMIN_USERNAME" --password "$KEYCLOAK_ADMIN_PASSWORD" > /dev/null
web_uuid=$("$KCADM" get clients -r diligence -q clientId=diligence-web --fields id --format csv --noquotes)
"$KCADM" update "clients/$web_uuid" -r diligence \
  -s "redirectUris=[\"http://localhost:3000/*\",\"$APP_BASE_URL/*\"]" -s "webOrigins=[\"$APP_BASE_URL\"]" \
  -s "attributes.\"post.logout.redirect.uris\"=$APP_BASE_URL/*"
echo "setup: redirect URI $APP_BASE_URL/* registered"

infrastructure/docker/scripts/demo-setup.sh

for attempt in 1 2 3; do
  if DEMO_REDIRECT_URI=http://localhost:3000/auth/callback python3 -I infrastructure/docker/scripts/demo-seed.py; then
    echo "setup: demo ready at $APP_BASE_URL"
    exit 0
  fi
  echo "setup: seeding failed (attempt $attempt), retrying in 30 s"
  sleep 30
done
echo "setup: demo data could not be created" >&2
exit 1
