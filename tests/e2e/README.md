# End-to-end tests (Playwright)

Black-box tests in a real browser against a running stack: the frontend (BFF), the backend, Keycloak and the data
services. They cover the [UAT scenarios](../../docs/uat/scenarios.md) marked **Auto**.

| Spec | What it checks |
|------|----------------|
| `smoke.spec.ts` | Health endpoint; pages require sign-in |
| `login.spec.ts` | The project manager signs in and gets the consultant navigation |
| `auth.setup.ts` | Signs in once per role (OTP included) and saves the sessions for the specs below |
| `barriers.spec.ts` | Navigation and direct URLs per role: the target never reaches findings or reports, the client never reaches the data room or request list |
| `mvp-flow.spec.ts` | One engagement end to end, in order. 1–2: release, delivery by the target. 3–6: finding with a cited source, four-eyes review, release, client view. 7–10: Q&A through approval, answer with upload, close. 11–12: report issued, watermarked PDF for the client. |

## Users

The flow needs one user per role. On the local stack they come from `make keycloak-bootstrap` with these values in
`.env`:

| Variable | Purpose |
|----------|---------|
| `KEYCLOAK_DEV_PM`, `KEYCLOAK_DEV_PM_PASSWORD` | Project manager (already used for local development) |
| `KEYCLOAK_E2E_PASSWORD` | Password of `e2e.partner`, `e2e.analyst`, `e2e.target`, `e2e.client` |
| `KEYCLOAK_E2E_TOTP_SECRET` | OTP secret of the target and client users, who must use MFA. The tests compute the codes from it. |

The same variables without the `KEYCLOAK_` prefix (`E2E_PASSWORD`, `E2E_TOTP_SECRET`, `E2E_PM_USER`,
`E2E_PM_PASSWORD`) override them, for example in CI against a deployed environment.

## Running locally

```bash
make up && make keycloak-bootstrap          # with KEYCLOAK_E2E_* set in .env
make backend-run                            # separate terminal
cd apps/frontend && npm run build && OIDC_ALLOW_HTTP=true npm start   # separate terminal
```

```bash
cd tests/e2e && npm ci && npx playwright install chromium
```

```bash
cd tests/e2e && set -a && source ../../.env && set +a && npx playwright test
```

Before each run, `global-setup.ts` recreates project **E2E-001** with `fixtures/seed.sh`: all e2e users as members,
a Legal folder the target may upload into, and one unreleased request item. Remove it with
`fixtures/seed.sh cleanup`.

To use an installed Google Chrome instead of downloading Chromium, set `E2E_CHANNEL=chrome`.

## Against a deployed environment

Set the following, and the flow then runs in that project:

- `E2E_BASE_URL`;
- `E2E_SEED=0`;
- `E2E_PROJECT_ID`: a prepared project with the same shape as E2E-001;
- the user variables above, for users that exist there.

The flow changes data (releases, findings, an issued report), so use a dedicated project on dev or staging, never
production.

## Notes

- Labels are matched in Indonesian or English, straight from `apps/frontend/messages` (`support/i18n.ts`). The specs
  keep working whichever language a user's profile has.
- Keycloak locks a user who signs in twice within a second, and refuses an OTP code that was already used. Hence one
  sign-in per role in `auth.setup.ts`. A rerun within 30 seconds waits for the next code.
