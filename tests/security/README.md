# Security tests (OWASP ZAP)

| File | Purpose |
|------|---------|
| `baseline.conf` | Rules of the passive baseline scan; `FAIL` rules break the build |
| `zap-login.py` | Signs a user in for the authenticated scans: the web app's session cookie (through the BFF) and an API access token (Authorization Code + PKCE) |
| `zap-gate.py` | Fails on High-risk alerts or on hit `FAIL` rules; writes the GitHub step summary |

## In CI

`.github/workflows/dast.yml` runs nightly and on demand (`gh workflow run DAST --ref main`, issue #67):

1. starts the full compose stack (`--profile app`) with throw-away secrets and creates the Keycloak users;
2. signs in as the local project manager (no OTP) and checks the session and the token work;
3. **baseline scan** of the web app from `/dashboard`, signed in (sign-in/out and Keycloak's own pages excluded);
4. **API scan** of the backend from `/v3/api-docs` with the bearer token (active, at most 30 minutes; the per-user
   rate limit is off for the run);
5. gate, and the HTML/JSON reports as the `zap-reports` artifact (30 days).

## Locally

```bash
make zap-baseline                                  # passive scan of the running frontend
ZAP_COOKIE='diligence_sid=…' make zap-baseline     # signed in
```
