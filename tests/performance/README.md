# Performance tests (k6)

`load.js` checks the performance targets of PRD Bab 7 under load:

| Scenario | What it does | Threshold (P95) |
|----------|--------------|-----------------|
| `pages` | Signed-in users browse the project pages through the BFF: server rendering plus the API calls behind it | page ≤ 2 s |
| `preview` | The document viewer loads watermarked page images, which are stamped for the viewer on every request | preview ≤ 3 s |
| `api` | Direct API traffic at a fixed rate: full-text search, and the lists behind the pages | search ≤ 2 s, API ≤ 1 s |

All scenarios also require fewer than 1% failed requests and more than 99% passed checks.

Sign-in uses the real flows, in `setup()`. App sessions come from `/auth/login` → Keycloak → `/auth/callback`, one
per browsing VU. The API token comes from Authorization Code + PKCE against the public `diligence-web` client. No
test-only client or grant is needed.

## Running locally

```bash
make up && make keycloak-bootstrap        # infrastructure and the local PM
tests/performance/seed/seed.sh            # 20 projects PERF-001…020, 10,000 indexed documents; prints a PROJECT_ID
```

Run the backend the way production does. The `local` profile logs `com.inpartnerhub.diligence` at DEBUG and samples
every trace. At 20 VUs that alone pushed page P95 from 0.2 s to 2.2 s and P99 to 10 s.

```bash
LOGGING_LEVEL_COM_INPARTNERHUB_DILIGENCE=INFO MANAGEMENT_TRACING_SAMPLING_PROBABILITY=0.05 make backend-run
```

Then build the frontend and run k6. Do not use `next dev` for this.

```bash
cd apps/frontend && npm run build && OIDC_ALLOW_HTTP=true npm start
```

```bash
cd tests/performance && K6_USER=pm.local K6_PASSWORD=<from .env> PROJECT_ID=<PERF-001 id> PREVIEW_PROJECT_ID=<project with rendered documents> k6 run -e PROFILE=baseline load.js
```

Clean up the seeded data afterwards:

```bash
tests/performance/seed/seed.sh cleanup
```

| Variable | Default | Meaning |
|----------|---------|---------|
| `PROFILE` | `smoke` | `smoke` (2 page VUs, 30 s), `baseline` (20 / 5 / 10 rps, 3 min), `stress` (60 / 15 / 30 rps, 4 min) |
| `APP_URL` | `http://localhost:3000` | Frontend (BFF) |
| `API_URL` | `http://localhost:18080` | Backend, or Kong in deployed environments |
| `OIDC_ISSUER` | `http://localhost:8180/realms/diligence` | Keycloak realm |
| `PROJECT_ID` | — | Project the pages and API calls load (a seeded `PERF-*` project) |
| `PREVIEW_PROJECT_ID` | `PROJECT_ID` | Project with rendered documents. Seeded documents have no stored files. |
| `SEARCH_TERMS` | `akta,kontrak,…` | Comma-separated search queries |

Things to know:

- **Keycloak brute-force detection.** Two password logins of the same user less than a second apart lock the user
  for a minute (`quickLoginCheckMilliSeconds`). So the password is entered once. Every further session and the API
  token reuse that Keycloak SSO session, like a second browser tab.
- **Token lifetime.** Access tokens live 5 minutes and are fetched once in `setup()`, so every profile stays shorter
  than that.
- **Cookies.** k6 clears each VU's cookie jar between iterations unless `noCookiesReset` is set. `load.js` sets it so
  each VU keeps its session like a browser tab.
- **Kong rate limit.** Kong allows 600 requests per minute per client address. Run against the backend directly, or
  exempt the load generator's address, or the `api` scenario measures 429s.

## Deployed environments

The **Performance** workflow (`.github/workflows/performance.yml`, manual) runs `load.js` in the `grafana/k6` image
against `dev` or `staging`. It needs these settings in the GitHub environment:

- variables `PERF_APP_URL`, `PERF_API_URL`, `PERF_OIDC_ISSUER`;
- secrets `PERF_USER`, `PERF_PASSWORD` for a test user without OTP that manages the project.

The summary is uploaded as an artifact.

## Results

Local stack (MacBook, single Next.js process, single backend, compose services). 20 seeded projects with 10,000
indexed documents. Backend run as described above.

| Profile | Page P95 | Preview P95 | Search P95 | API P95 | Requests/s | Failed |
|---------|----------|-------------|------------|---------|------------|--------|
| baseline, before | 219 ms | 166 ms | 55 ms | 69 ms | 21 | 0% |
| stress, before | **7.06 s** ✗ | **4.02 s** ✗ | 225 ms | 332 ms | 44 | 0% |
| baseline, after | 262 ms | 206 ms | 96 ms | 114 ms | 21 | 0% |
| stress, after | 0.90 s | 0.46 s | 128 ms | 252 ms | 57 | 0% |

Under stress the bottleneck was the BFF, not the API. 87% of every page (481 of 554 KB) was inline CSS that Ant
Design's CSS-in-JS generated per request. The frontend now extracts the theme's CSS once at build time
(`apps/frontend/scripts/extract-antd-css.mjs`) and runs antd in zero-runtime mode. Pages are about 100 KB and render
in roughly a third of the time.

Runs vary with what else the machine is doing. One baseline run with the load average around 7 reached a page P95
of 835 ms. Compare runs made under the same conditions. These numbers are a baseline for one machine. The NFR targets (1,000 concurrent users, 500,000 documents) have to be
confirmed on staging with production-sized data and replicas.
