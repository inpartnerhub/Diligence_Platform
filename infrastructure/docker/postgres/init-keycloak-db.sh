#!/bin/sh
# Runs once on fresh PostgreSQL initialization to create the dedicated Keycloak role and database.
set -eu

KC_USER="${KEYCLOAK_DB_USER:-keycloak}"
KC_DB="${KEYCLOAK_DB_NAME:-keycloak}"
KC_PASS="${KEYCLOAK_DB_PASSWORD:?KEYCLOAK_DB_PASSWORD must be set}"

psql -v ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  -v kc_user="$KC_USER" \
  -v kc_db="$KC_DB" \
  -v kc_pass="$KC_PASS" <<'SQL'
SELECT format('CREATE ROLE %I WITH LOGIN PASSWORD %L', :'kc_user', :'kc_pass')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'kc_user')
\gexec

SELECT format('CREATE DATABASE %I OWNER %I', :'kc_db', :'kc_user')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'kc_db')
\gexec
SQL
