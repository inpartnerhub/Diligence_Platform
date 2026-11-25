# Secret rotation

Secrets come from AWS Secrets Manager, synced into Kubernetes Secrets by the External Secrets Operator (ESO) via EKS
Pod Identity (ADR-0011); none are in Git (gitleaks in CI). Terraform owns the generated infrastructure credentials
(`diligence/<env>/backend/infrastructure`, `diligence/<env>/frontend-redis`); operators own the rest
(`diligence/<env>/backend/application`, `diligence/<env>/frontend`). The base manifests reference
`diligence/base/...`; each overlay patches the key to its environment.

## Routine (at least yearly, and after any suspected exposure)

1. Write the new value to the Secrets Manager secret: `diligence/<env>/backend/infrastructure` (database, Redis
   connection) or `diligence/<env>/backend/application` (Keycloak admin client secret, SMTP, …); the frontend's
   `SESSION_SECRET` lives in `diligence/<env>/frontend`.
2. ESO syncs the new value into the Kubernetes Secret within its refresh interval (1 min). Restart the consuming
   deployment so it is read (env vars from `envFrom` are read at startup): `kubectl -n diligence-<env> rollout restart
   deploy/<name>`.
3. Verify: pods ready, no authentication errors in logs, sign-in works.
4. Revoke the old credential at the source (RDS user password, Keycloak client secret).

Rotating `SESSION_SECRET` signs every user out of the web application; announce it.

## Web client secret (#175)

`OIDC_CLIENT_SECRET` (in `diligence/<env>/frontend`) authenticates the frontend to Keycloak as the confidential
client `diligence-web`. Keycloak holds one secret per client, so a rotation briefly fails sign-ins and token refreshes
until the frontend has restarted with the new value:

1. Keycloak admin console → Clients → `diligence-web` → Credentials → *Regenerate*; copy the new secret.
2. Write it to Secrets Manager as `OIDC_CLIENT_SECRET`; once ESO has synced it, restart the frontend.
3. Update the GitHub secret `PERF_OIDC_CLIENT_SECRET` of that environment (performance tests).

Sessions whose refresh fails sign in again. Rotate outside business hours.

## Session encryption key (#172)

The frontend encrypts the session records it keeps in Redis (access, refresh and ID tokens) with
`SESSION_ENCRYPTION_KEY` (base64, 32 bytes, in `diligence/<env>/frontend`). Each record names the key it was written
with. To rotate it without signing users out:

1. In Secrets Manager, copy the current value to `SESSION_ENCRYPTION_KEY_PREVIOUS` and set a new
   `SESSION_ENCRYPTION_KEY` (`openssl rand -base64 32`). Once ESO has synced it, restart the frontend; new and
   refreshed sessions use the new key, existing ones stay readable with the previous key.
2. After 10 hours (the maximum session age) no session written with the old key is left: remove
   `SESSION_ENCRYPTION_KEY_PREVIOUS`.

After a suspected exposure skip the waiting: set a new key without `_PREVIOUS`, which signs every user out.

## Redis credentials (#171)

Redis (ElastiCache) has one user per workload: `backend` and `web` (the BFF; only it reads sessions); access strings
in `modules/redis/access-strings.json`. Each password is a `random_password.user["<name>"]` in `modules/redis`: taint
it and apply. Terraform updates the ElastiCache user and writes the new value to Secrets Manager
(`backend/infrastructure` for `backend`, `frontend-redis` for `web`); once ESO has synced it, restart the backend or
frontend. Check afterwards that the logs show no Redis authentication errors.

## Webhook signing keys (ADM-07)

`/admin/webhooks` → key button → *Generate new signing key*. The key is shown once; give it to the receiver's owner
over a secure channel. The old key stops working immediately, so coordinate the switch with the receiver.

## Webhook key-encryption key (#64)

Webhook signing keys are stored encrypted with `WEBHOOK_KEY_ENCRYPTION_KEY` (base64, 32 bytes, in
`diligence/<env>/backend/application`). To rotate it:

1. In Secrets Manager, copy the current value to `WEBHOOK_KEY_ENCRYPTION_KEY_PREVIOUS` and set a new
   `WEBHOOK_KEY_ENCRYPTION_KEY` (`openssl rand -base64 32`).
2. Once ESO has synced it, restart the backend; at startup every signing key is re-encrypted with the new key
   ("Encrypted N webhook signing keys" in the log).
3. Remove `WEBHOOK_KEY_ENCRYPTION_KEY_PREVIOUS`.

Never delete the current key without a replacement: stored signing keys could no longer be decrypted and every
webhook would need a new signing key (key button in `/admin/webhooks`).

## KMS keys

Customer-managed KMS keys (S3, RDS, logs, Secrets Manager) rotate automatically every year (`modules/kms-key`). No
action needed.
