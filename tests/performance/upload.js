// Bulk upload through the ingestion pipeline (PRD Bab 7 and Bab 14: a data room of 10,000 files).
//
//   K6_USER=pm.local K6_PASSWORD=… PROJECT_ID=<uuid> k6 run [-e UPLOAD_FILES=10000] upload.js
//
// setup() creates a fresh folder. UPLOAD_VUS uploaders then send UPLOAD_FILES distinct one-page PDFs to the API, the way
// the data room's folder upload does (one request per file). teardown() waits until the pipeline (ClamAV scan, Tika
// type detection and text extraction, Gotenberg page images, search indexing) has finished every file and reports:
//   upload request time       http_req_duration{kind:upload}
//   files per outcome         ingested_clean / ingested_rejected (counters)
//   searchable                ingested_searchable — share of a sample of files found by their unique text
//   upload request time       upload_duration (trend, also without a threshold)
//   pipeline drain            ingestion_seconds — from the first upload until the last file left PENDING
//                             ingestion_backlog_seconds — from the last upload until then
//   throughput                ingestion_files_per_minute
// See README.md for how to run it and what to watch on the backend while it runs.
import http from "k6/http";
import { check, sleep } from "k6";
import exec from "k6/execution";
import { Counter, Gauge, Rate, Trend } from "k6/metrics";

import { apiTokens, bearer } from "./lib/auth.js";
import { marker, onePagePdf } from "./lib/pdf.js";

const APP_URL = __ENV.APP_URL || "http://localhost:3000";
const API_URL = __ENV.API_URL || "http://localhost:18080";
const ISSUER = __ENV.OIDC_ISSUER || "http://localhost:8180/realms/diligence";
const PROJECT = __ENV.PROJECT_ID;
const USER = __ENV.K6_USER;
const PASSWORD = __ENV.K6_PASSWORD;
const FILES = Number(__ENV.UPLOAD_FILES || 10000);
const VUS = Number(__ENV.UPLOAD_VUS || 20);
const INGEST_TIMEOUT_MINUTES = Number(__ENV.INGEST_TIMEOUT_MINUTES || 120);
const POLL_SECONDS = 15;
const SEARCH_SAMPLE = 20;

const clean = new Counter("ingested_clean");
const rejected = new Counter("ingested_rejected");
const ingestionSeconds = new Gauge("ingestion_seconds");
const backlogSeconds = new Gauge("ingestion_backlog_seconds");
const filesPerMinute = new Gauge("ingestion_files_per_minute");
const searchable = new Rate("ingested_searchable");
const uploadDuration = new Trend("upload_duration", true);

export const options = {
  setupTimeout: "2m",
  teardownTimeout: `${INGEST_TIMEOUT_MINUTES + 5}m`,
  scenarios: {
    upload: {
      executor: "shared-iterations",
      exec: "upload",
      vus: Math.min(VUS, FILES),
      iterations: FILES,
      maxDuration: "2h",
    },
  },
  thresholds: {
    "http_req_failed{kind:upload}": ["rate<0.01"],
    "http_req_failed{kind:poll}": ["rate<0.01"],
    // Every uploaded file must come out of the pipeline CLEAN (no rejection, failure or timeout).
    ingested_clean: [`count>=${FILES}`],
    ingested_rejected: ["count==0"],
    // CLEAN is not the end of the pipeline: indexing runs after it. A sample of files must be found by search.
    ingested_searchable: ["rate==1"],
    checks: ["rate>0.99"],
  },
  summaryTrendStats: ["avg", "med", "p(90)", "p(95)", "p(99)", "max"],
};

export async function setup() {
  if (!PROJECT || !USER || !PASSWORD || !__ENV.OIDC_CLIENT_SECRET) {
    throw new Error("Set PROJECT_ID, K6_USER, K6_PASSWORD and OIDC_CLIENT_SECRET");
  }
  const tokens = await apiTokens({
    issuer: ISSUER,
    clientId: __ENV.OIDC_CLIENT_ID || "diligence-web",
    clientSecret: __ENV.OIDC_CLIENT_SECRET,
    redirectUri: `${APP_URL}/auth/callback`,
    username: USER,
    password: PASSWORD,
  });
  const name = `Bulk upload ${new Date().toISOString().replace(/[:.]/g, "-")}`;
  const folder = http.post(
    `${API_URL}/api/v1/projects/${PROJECT}/folders`,
    JSON.stringify({ name }),
    {
      headers: { Authorization: bearer(tokens), "Content-Type": "application/json" },
      tags: { kind: "setup" },
    },
  );
  if (folder.status !== 201 && folder.status !== 200) {
    throw new Error(`creating folder "${name}" failed: ${folder.status} ${folder.body}`);
  }
  console.log(`Uploading ${FILES} files into folder "${name}" with ${VUS} uploaders`);
  // A run id makes every file's search marker unique across runs.
  return { tokens, folderId: folder.json("id"), startedAt: Date.now(), run: Date.now().toString(36) };
}

export function upload(data) {
  const n = exec.scenario.iterationInTest + 1;
  const name = `bulk-${String(n).padStart(5, "0")}.pdf`;
  const res = http.post(
    `${API_URL}/api/v1/projects/${PROJECT}/folders/${data.folderId}/documents`,
    { file: http.file(onePagePdf(n, data.run), name, "application/pdf") },
    { headers: { Authorization: bearer(data.tokens) }, tags: { kind: "upload", name: "upload" } },
  );
  uploadDuration.add(res.timings.duration);
  check(res, {
    "upload accepted": (r) => r.status === 201,
    "new document": (r) => r.status !== 201 || (!r.json("unchanged") && r.json("duplicateOf").length === 0),
  });
}

/** Waits for the pipeline to finish every uploaded file, then records how long that took. */
export function teardown(data) {
  const uploadsDone = Date.now();
  const deadline = uploadsDone + INGEST_TIMEOUT_MINUTES * 60 * 1000;
  let counts = {};
  for (;;) {
    const res = http.get(
      `${API_URL}/api/v1/projects/${PROJECT}/folders/${data.folderId}/documents`,
      { headers: { Authorization: bearer(data.tokens) }, tags: { kind: "poll", name: "folder documents" } },
    );
    if (res.status === 200) {
      counts = {};
      for (const d of res.json()) counts[d.scanStatus] = (counts[d.scanStatus] || 0) + 1;
      const total = Object.values(counts).reduce((a, b) => a + b, 0);
      console.log(`ingestion: ${total} documents, ${JSON.stringify(counts)}`);
      if (total > 0 && !counts.PENDING) break;
    }
    if (Date.now() > deadline) {
      console.error(`Ingestion did not finish within ${INGEST_TIMEOUT_MINUTES} minutes`);
      break;
    }
    sleep(POLL_SECONDS);
  }
  // Finishing is only known to the poll interval.
  const finished = Date.now();
  clean.add(counts.CLEAN || 0);
  rejected.add((counts.REJECTED || 0) + (counts.INFECTED || 0) + (counts.FAILED || 0));
  const seconds = (finished - data.startedAt) / 1000;
  ingestionSeconds.add(seconds);
  backlogSeconds.add((finished - uploadsDone) / 1000);
  filesPerMinute.add(((counts.CLEAN || 0) / seconds) * 60);
  checkSearchable(data);
}

/** Searches the unique marker of a sample of files; indexing follows CLEAN, so allow it a little time. */
function checkSearchable(data) {
  const headers = { Authorization: bearer(data.tokens) };
  const sample = Math.min(SEARCH_SAMPLE, FILES);
  for (let i = 0; i < sample; i++) {
    const n = 1 + Math.floor((i * FILES) / sample);
    let found = false;
    for (let attempt = 0; attempt < 4 && !found; attempt++) {
      if (attempt > 0) sleep(POLL_SECONDS);
      const res = http.get(
        `${API_URL}/api/v1/projects/${PROJECT}/search?q=${marker(data.run, n)}`,
        { headers, tags: { kind: "poll", name: "search marker" } },
      );
      found = res.status === 200 && res.json("total") > 0;
    }
    searchable.add(found);
    if (!found) console.error(`File ${n} is not searchable`);
  }
}
