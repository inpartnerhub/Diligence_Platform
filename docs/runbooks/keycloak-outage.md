# Keycloak outage / sign-in failing

**Alert:** `DiligenceKeycloakDown` (Prometheus cannot scrape Keycloak's metrics endpoint for 2 minutes).

## Symptoms

Users cannot sign in, the BFF shows the sign-in error page, the backend logs `JwtValidationException` or cannot
fetch the JWKS.

## Check

1. Keycloak health: `/health/ready` on its management port 9000; database connectivity.
2. Issuer mismatch after a host name change: `OIDC_ISSUER_URI` must equal the token `iss`.
3. Clock skew between nodes (tokens live 5 min).

## Mitigate

- Restart or scale Keycloak; signed-in users keep working until their access token expires because the backend
  caches the signing keys.
- SSO broker (Entra ID / Google) down: internal users sign in with password + TOTP if they have one; external users
  are unaffected.
- Never disable MFA or brute-force protection to "get users in" (PRD ADM-02).
