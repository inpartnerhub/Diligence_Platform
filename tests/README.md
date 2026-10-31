# Tests

Test pyramid and where each layer lives:

| Layer | Location | Runs in CI | Needs |
|-------|----------|-----------|-------|
| Unit | `apps/backend/src/test` (JUnit 5), frontend colocated `*.test.ts` | every PR | nothing |
| Module boundaries | `apps/backend/.../ModularityTests.java` (Spring Modulith) | every PR | nothing |
| Integration | `apps/backend` tests tagged `integration` (Testcontainers): Flyway + Hibernate, RLS information barriers, audit immutability | every PR | Docker |
| Web security slice | `AdminEndpointSecurityTest` (`@WebMvcTest`): 401/403 per role | every PR | nothing |
| Frontend unit | `apps/frontend/src/**/*.test.ts` (Vitest): role navigation, token parsing | every PR | nothing |
| Cross-project access | `CrossProjectAccessIntegrationTest`: every object ID of project B through project A's URL and by outsiders (IDOR) | every PR | Docker |
| Client address | `ClientAddressIntegrationTest` (real Tomcat): forged `X-Forwarded-For` entries are ignored | every PR | Docker |
| Passive DAST | `tests/security` (OWASP ZAP baseline, `make zap-baseline`) | before releases | running stack |
| End-to-end | `tests/e2e` (Playwright) | after deploy to dev/staging | running stack |
| Performance | `tests/performance` (k6) | on demand / nightly against staging | running stack + token |

```bash
cd tests/e2e && npm install && npx playwright install chromium
E2E_PM_USER=pm.local E2E_PM_PASSWORD=<from .env> npm test   # login spec needs `make up-all && make keycloak-bootstrap`
```
