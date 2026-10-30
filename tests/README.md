# Tests

Test pyramid and where each layer lives:

| Layer | Location | Runs in CI | Needs |
|-------|----------|-----------|-------|
| Unit | `apps/backend/src/test` (JUnit 5), frontend colocated `*.test.ts` | every PR | nothing |
| Module boundaries | `apps/backend/.../ModularityTests.java` (Spring Modulith) | every PR | nothing |
| Integration | `apps/backend` tests tagged `integration` (Testcontainers) | every PR | Docker |
| End-to-end | `tests/e2e` (Playwright) | after deploy to dev/staging | running stack |
| Performance | `tests/performance` (k6) | on demand / nightly against staging | running stack + token |

```bash
cd tests/e2e && npm install && npx playwright install chromium && npm test
```
