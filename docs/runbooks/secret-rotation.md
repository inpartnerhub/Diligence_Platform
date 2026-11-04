# Secret rotation

Secrets come from HashiCorp Vault via the Vault Secrets Operator (ADR-0002); none are in Git (gitleaks in CI).

## Routine (at least yearly, and after any suspected exposure)

1. Write the new value to Vault under `diligence/base/backend/infrastructure` (database, RabbitMQ, Redis,
   OpenSearch credentials) or `diligence/base/backend/application` (Keycloak admin client secret, …); the frontend's
   `SESSION_SECRET` lives under `diligence/base/frontend` (overlays may change the prefix).
2. The operator updates the Kubernetes secret and triggers a rolling restart of the consuming deployment.
3. Verify: pods ready, no authentication errors in logs, sign-in works.
4. Revoke the old credential at the source (RDS user password, Keycloak client secret).

Rotating `SESSION_SECRET` signs every user out of the web application; announce it.

## Webhook signing keys (ADM-07)

`/admin/webhooks` → key button → *Generate new signing key*. The key is shown once; give it to the receiver's owner
over a secure channel. The old key stops working immediately, so coordinate the switch with the receiver.

## KMS keys

Customer-managed KMS keys (S3, RDS, logs) rotate automatically every year (`modules/kms-key`). No action needed.
