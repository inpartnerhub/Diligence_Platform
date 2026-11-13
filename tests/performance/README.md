# Performance tests (k6)

| Script | What it proves |
|--------|----------------|
| `load.js` | Response times of PRD Bab 7 under concurrent users (below) |
| `upload.js` | A bulk upload of 10,000 files through the ingestion pipeline (see [Bulk upload](#bulk-upload)) |

`load.js` checks the performance targets of PRD Bab 7 under load:

| Scenario | What it does | Threshold (P95) |
|----------|--------------|-----------------|
| `pages` | Signed-in users browse the project pages through the BFF: server rendering plus the API calls behind it | page ≤ 2 s |
| `preview` | The document viewer loads watermarked page images, which are stamped for the viewer on every request | preview ≤ 3 s |
| `api` | Direct API traffic at a fixed rate: full-text search, and the lists behind the pages | search ≤ 2 s, API ≤ 1 s |

All scenarios also require fewer than 1% failed requests and more than 99% passed checks.

Sign-in uses the real flows, in `setup()`. App sessions come from `/auth/login` → Keycloak → `/auth/callback`, one
per browsing VU. The API token comes from Authorization Code + PKCE against the confidential `diligence-web` client (with its secret, #175). No
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
| `PROFILE` | `smoke` | `smoke` (2 page VUs, 30 s), `baseline` (20 / 5 / 10 rps, 3 min), `stress` (60 / 15 / 30 rps, 4 min), `prd` (950 page + 50 preview VUs = 1,000 users, 100 rps, 5 min ramp + 15 min; staging only) |
| `SESSIONS` | VUs, at most 200 for `prd` | App sessions opened in `setup()`. VUs share them round-robin, like tabs of one browser. |
| `APP_URL` | `http://localhost:3000` | Frontend (BFF) |
| `API_URL` | `http://localhost:18080` | Backend, or Kong in deployed environments |
| `OIDC_ISSUER` | `http://localhost:8180/realms/diligence` | Keycloak realm |
| `OIDC_CLIENT_SECRET` | — | Secret of the confidential `diligence-web` client (#175), for the API token: `OIDC_CLIENT_SECRET` from `.env` locally |
| `PROJECT_ID` | — | Project the pages and API calls load (a seeded `PERF-*` project) |
| `PREVIEW_PROJECT_ID` | `PROJECT_ID` | Project with rendered documents. Seeded documents have no stored files. |
| `PREVIEW_FOLDER_ID` | — | Only look for rendered documents in this folder, e.g. the one `upload.js` filled. Use it with a large seeded project. |
| `PREVIEW_PAGES` | `200` | Page images collected in `setup()` for the viewer scenario |
| `SEARCH_TERMS` | `akta,kontrak,…` | Comma-separated search queries |

Things to know:

- **Keycloak brute-force detection.** Two password logins of the same user less than a second apart lock the user
  for a minute (`quickLoginCheckMilliSeconds`). So the password is entered once. Every further session and the API
  token reuse that Keycloak SSO session, like a second browser tab.
- **Token lifetime.** Access tokens live 5 minutes. The API token from `setup()` is refreshed by each VU a minute
  before it expires (`lib/auth.js`, `bearer()`); the realm does not rotate refresh tokens, so all VUs can refresh from
  the same one. App sessions refresh their tokens in the BFF.
- **Cookies.** k6 clears each VU's cookie jar between iterations unless `noCookiesReset` is set. `load.js` sets it so
  each VU keeps its session like a browser tab.
- **Kong rate limit.** Kong allows 600 requests per minute per client address. Run against the backend directly, or
  exempt the load generator's address, or the `api` scenario measures 429s.
- **Per-user rate limit.** The backend also limits each user (600 requests per minute, 60 for search, downloads and
  exports). All VUs sign in as one account, so start the backend with `USER_RATE_LIMIT_ENABLED=false` for load runs.
- **Response bodies.** Page and page-image bodies are discarded (`responseType: "none"`); only status, URL and
  headers are checked. That keeps 1,000 VUs within one load generator's memory.

## Bulk upload

`upload.js` creates a folder named `Bulk upload <timestamp>` in `PROJECT_ID` and uploads `UPLOAD_FILES` distinct
one-page PDFs with `UPLOAD_VUS` parallel uploaders, one request per file like the data room's folder upload. Every file
has its own text (`lib/pdf.js`), so duplicate detection does not short-cut the pipeline. `teardown()` then polls the
folder until no file is `PENDING` and searches the unique text of 20 sampled files.

```bash
cd tests/performance && K6_USER=pm.local K6_PASSWORD=<from .env> PROJECT_ID=<PERF-001 id> k6 run upload.js
```

| Metric | Meaning | Threshold |
|--------|---------|-----------|
| `upload_duration` | Time per upload request (stored and queued, not yet scanned) | — |
| `ingested_clean` / `ingested_rejected` | Files that came out CLEAN / INFECTED, REJECTED or FAILED | all CLEAN, none rejected |
| `ingested_searchable` | Sampled files found by full-text search (indexing follows CLEAN) | 100% |
| `ingestion_seconds` | First upload until the last file left `PENDING` (to the 15 s poll interval) | — |
| `ingestion_backlog_seconds` | Last upload until then: how far the pipeline fell behind the uploads | — |
| `ingestion_files_per_minute` | Throughput of the whole pipeline | — |

| Variable | Default | Meaning |
|----------|---------|---------|
| `UPLOAD_FILES` | `10000` | Files to upload |
| `UPLOAD_VUS` | `20` | Parallel uploaders |
| `INGEST_TIMEOUT_MINUTES` | `120` | How long `teardown()` waits for the pipeline |

The files stay in the project afterwards: use their folder as `PREVIEW_FOLDER_ID` for `load.js`, then remove them
with the seed cleanup. While it runs, watch the ingestion queue depth in RabbitMQ and the ClamAV, Tika and Gotenberg
replicas; those set the throughput.

## PRD scale on staging

PRD Bab 7 and Bab 14 ask for 1,000 concurrent users on 500,000 documents and a bulk upload of 10,000 files. A laptop
cannot hold that (the local compose stack needs more than 4 GiB for the pipeline alone), so this run is for staging
with production-like sizing.

1. **Seed 500,000 documents.** `SEED_SCALE=prd` creates 50 projects. PERF-001 is the large data room with 50,000
   documents, the others hold the rest. Run it where the database and OpenSearch are reachable (the bastion, or port
   forwards); it needs `psql` and `curl`.

   ```bash
   SEED_SCALE=prd SEED_MEMBER_EMAIL=<perf user email> \
     SEED_DATABASE_URL='postgresql://<owner>@<host>:5432/<db>?sslmode=require' \
     OPENSEARCH_URL=https://<domain endpoint> OPENSEARCH_USERNAME=<master user> OPENSEARCH_PASSWORD=<…> \
     tests/performance/seed/seed.sh
   ```

   It indexes one project at a time with refresh switched off and restores the index's refresh interval afterwards.
   Run `cleanup` first if PERF-* projects exist.
2. **Prepare the environment.** Start the backend with `USER_RATE_LIMIT_ENABLED=false` (all VUs share one account) and
   exempt the load generator from Kong's per-IP limit, or point `API_URL` past Kong. Use INFO logging and production
   trace sampling.
3. **Bulk upload** with the **Performance** workflow: `test=upload`, `upload-files=10000`, `project-id=<PERF-001>`.
4. **Load** with `test=load`, `profile=prd`, `project-id=<PERF-001>`, `preview-project-id=<PERF-001>` and a runner with
   at least 4 vCPU and 8 GiB (`runner` input; a GitHub larger runner or a self-hosted runner in the VPC). Set
   `preview-folder-id` to the bulk-upload folder so `setup()` does not walk 50,000 seeded documents.
5. **Record** the summaries in [Results](#results) with the environment's sizing, and open an issue for every target
   that is missed.
6. **Clean up** with `tests/performance/seed/seed.sh cleanup` (same connection variables). It prints the object
   storage prefixes of the PERF-* projects, whose uploaded files and renditions have to be deleted separately.

## Deployed environments

The **Performance** workflow (`.github/workflows/performance.yml`, manual) runs `load.js` or `upload.js` in the
`grafana/k6` image against `dev` or `staging`. It needs these settings in the GitHub environment:

- variables `PERF_APP_URL`, `PERF_API_URL`, `PERF_OIDC_ISSUER`;
- secrets `PERF_USER`, `PERF_PASSWORD` for a test user without OTP that manages the project, and
  `PERF_OIDC_CLIENT_SECRET`, the environment's `diligence-web` client secret (#175).

The summary is uploaded as an artifact. The job may take up to 5 hours, the limit of an upload run.

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

Bulk upload, local stack with the backend on the host (as above, ClamAV, Tika and Gotenberg one replica each, 4 GiB
for Docker). A rehearsal of the script, not a measurement of the target:

| Files | Uploaders | Upload P95 | All CLEAN | Searchable | Pipeline drained | Files/min |
|-------|-----------|------------|-----------|------------|------------------|-----------|
| 150 | 5 | 133 ms | 150 / 150 | 20 / 20 | 32 s | 279 |

With every service in 4 GiB of Docker memory, OpenSearch and ClamAV were OOM-killed during uploads: files then end
`FAILED` or are not indexed, which the thresholds report.

Runs vary with what else the machine is doing. One baseline run with the load average around 7 reached a page P95
of 835 ms. Compare runs made under the same conditions. These numbers are a baseline for one machine. The NFR targets (1,000 concurrent users, 500,000 documents) have to be
confirmed on staging with production-sized data and replicas.
