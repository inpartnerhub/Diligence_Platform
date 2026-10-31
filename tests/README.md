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
| End-to-end | `tests/e2e` (Playwright): one engagement end to end in every role, information barriers ([README](e2e/README.md)) | before UAT and after deploys to dev/staging | running stack + e2e users |
| Performance | `tests/performance/load.js` (k6): pages, preview and search against the PRD Bab 7 targets; seed data in `tests/performance/seed` ([README](performance/README.md)) | on demand (Performance workflow) | running stack + test user |

See [e2e/README.md](e2e/README.md) for users and commands, and [performance/README.md](performance/README.md) for load tests.
