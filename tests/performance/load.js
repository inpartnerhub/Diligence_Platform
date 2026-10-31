// Load test against the NFRs of PRD Bab 7: pages ≤ 2 s P95, document preview ≤ 3 s, search ≤ 2 s.
//
//   K6_USER=pm.local K6_PASSWORD=… PROJECT_ID=<uuid> k6 run [-e PROFILE=baseline] load.js
//
// Three scenarios run side by side:
//   pages   — signed-in users browsing project pages through the BFF (server rendering + API calls behind it)
//   preview — the document viewer loading watermarked page images (rendered per request for the viewer)
//   api     — direct API traffic at a fixed rate: full-text search plus the lists behind the pages
// See README.md for profiles, environment variables and how to read the results.
import http from "k6/http";
import { check, sleep } from "k6";
import exec from "k6/execution";

import { apiToken, appSessions, useAppSession } from "./lib/auth.js";

const APP_URL = __ENV.APP_URL || "http://localhost:3000";
const API_URL = __ENV.API_URL || "http://localhost:18080";
const ISSUER = __ENV.OIDC_ISSUER || "http://localhost:8180/realms/diligence";
const PROJECT = __ENV.PROJECT_ID;
// Seeded projects have no stored files: page images come from a project with real, rendered documents.
const PREVIEW_PROJECT = __ENV.PREVIEW_PROJECT_ID || PROJECT;
const USER = __ENV.K6_USER;
const PASSWORD = __ENV.K6_PASSWORD;
const SEARCH_TERMS = (__ENV.SEARCH_TERMS || "akta,kontrak,laporan,modal,perseroan,saham").split(",");

// Access tokens live 5 minutes and are fetched once in setup(), so every profile stays below that.
const PROFILES = {
  smoke: { pages: 2, preview: 1, apiRate: 2, duration: "30s" },
  baseline: { pages: 20, preview: 5, apiRate: 10, duration: "3m" },
  stress: { pages: 60, preview: 15, apiRate: 30, duration: "4m" },
};
const profile = PROFILES[__ENV.PROFILE || "smoke"];
if (!profile) throw new Error(`Unknown PROFILE ${__ENV.PROFILE}; use ${Object.keys(PROFILES).join(", ")}`);

export const options = {
  setupTimeout: "2m",
  // Keep each VU's session cookie across iterations, like a browser tab (k6 clears the jar by default).
  noCookiesReset: true,
  scenarios: {
    pages: {
      executor: "ramping-vus",
      exec: "browsePages",
      stages: [
        { duration: "20s", target: profile.pages },
        { duration: profile.duration, target: profile.pages },
        { duration: "10s", target: 0 },
      ],
    },
    preview: {
      executor: "constant-vus",
      exec: "viewDocuments",
      vus: profile.preview,
      duration: profile.duration,
      startTime: "20s",
    },
    api: {
      executor: "constant-arrival-rate",
      exec: "callApi",
      rate: profile.apiRate,
      timeUnit: "1s",
      duration: profile.duration,
      startTime: "20s",
      preAllocatedVUs: Math.max(5, profile.apiRate),
      maxVUs: profile.apiRate * 4,
    },
  },
  thresholds: {
    "http_req_duration{kind:page}": ["p(95)<2000"],
    "http_req_duration{kind:preview}": ["p(95)<3000"],
    "http_req_duration{kind:search}": ["p(95)<2000"],
    "http_req_duration{kind:api}": ["p(95)<1000"],
    "http_req_failed{kind:page}": ["rate<0.01"],
    "http_req_failed{kind:preview}": ["rate<0.01"],
    "http_req_failed{kind:api}": ["rate<0.01"],
    "http_req_failed{kind:search}": ["rate<0.01"],
    checks: ["rate>0.99"],
  },
  summaryTrendStats: ["avg", "med", "p(90)", "p(95)", "p(99)", "max"],
};

const PAGES = [
  ["/", "home"],
  ["/projects/{p}", "project dashboard"],
  ["/projects/{p}/requests", "request list"],
  ["/projects/{p}/findings", "findings"],
  ["/projects/{p}/qna", "q&a"],
  ["/projects/{p}/reports", "reports"],
  ["/projects/{p}/data-room", "data room"],
  ["/notifications", "notifications"],
];

const API_LISTS = [
  ["/api/v1/projects", "projects"],
  ["/api/v1/projects/{p}/dashboard", "dashboard"],
  ["/api/v1/projects/{p}/requests", "requests"],
  ["/api/v1/projects/{p}/findings", "findings"],
  ["/api/v1/projects/{p}/questions", "questions"],
  ["/api/v1/projects/{p}/folders", "folders"],
  ["/api/v1/me/notifications/unread-count", "unread count"],
];

const pick = (list) => list[Math.floor(Math.random() * list.length)];
const withProject = (path) => path.replace("{p}", PROJECT);

export async function setup() {
  if (!PROJECT || !USER || !PASSWORD) throw new Error("Set PROJECT_ID, K6_USER and K6_PASSWORD");
  // One app session per browsing VU (see lib/auth.js for why they are all opened here).
  const sessions = appSessions(APP_URL, USER, PASSWORD, profile.pages + profile.preview);
  const token = await apiToken({
    issuer: ISSUER,
    clientId: __ENV.OIDC_CLIENT_ID || "diligence-web",
    redirectUri: `${APP_URL}/auth/callback`,
    username: USER,
    password: PASSWORD,
  });
  return { sessions, token, pages: previewablePages(token) };
}

/** Page images of every document in the project whose preview is ready (found once, before the load). */
function previewablePages(token) {
  const headers = { Authorization: `Bearer ${token}` };
  const params = { headers, tags: { kind: "setup" } };
  const project = `${API_URL}/api/v1/projects/${PREVIEW_PROJECT}`;
  const result = [];
  for (const folder of http.get(`${project}/folders`, params).json()) {
    const documents = http.get(`${project}/folders/${folder.id}/documents`, params);
    for (const document of documents.json().filter((d) => d.scanStatus === "CLEAN")) {
      const preview = http.get(`${project}/documents/${document.id}/preview`, params);
      if (preview.status !== 200 || preview.json("previewStatus") !== "READY") continue;
      const version = preview.json("versionId");
      for (let page = 1; page <= Math.min(preview.json("pageCount"), 5); page++) {
        result.push(
          `/api/projects/${PREVIEW_PROJECT}/documents/${document.id}/versions/${version}/pages/${page}`,
        );
      }
    }
  }
  if (result.length === 0)
    console.warn("No document with a ready preview: the preview scenario only signs in.");
  return result;
}

// Each browsing VU takes one of the sessions from setup() and keeps it, like a signed-in browser tab.
let signedIn = false;
function ensureSignedIn(data) {
  if (!signedIn) {
    useAppSession(APP_URL, data.sessions);
    signedIn = true;
  }
}

export function browsePages(data) {
  ensureSignedIn(data);
  const [path, name] = pick(PAGES);
  const res = http.get(`${APP_URL}${withProject(path)}`, {
    tags: { kind: "page", name },
  });
  check(res, {
    "page rendered": (r) => r.status === 200 && r.url.startsWith(APP_URL) && !r.url.includes("/auth/"),
  });
  sleep(1 + Math.random() * 2); // reading time
}

export function viewDocuments(data) {
  ensureSignedIn(data);
  if (data.pages.length === 0) {
    sleep(5);
    return;
  }
  // A viewer opens a document and pages through it.
  const first = Math.floor(Math.random() * data.pages.length);
  for (let i = 0; i < 3; i++) {
    const path = data.pages[(first + i) % data.pages.length];
    const res = http.get(`${APP_URL}${path}`, {
      tags: { kind: "preview", name: "page image" },
    });
    check(res, {
      "page image": (r) => r.status === 200 && r.headers["Content-Type"] === "image/png",
    });
    sleep(0.5 + Math.random());
  }
}

export function callApi(data) {
  const headers = {
    Authorization: `Bearer ${data.token}`,
    Accept: "application/json",
  };
  let res;
  if (exec.scenario.iterationInTest % 2 === 0) {
    const q = encodeURIComponent(pick(SEARCH_TERMS));
    res = http.get(`${API_URL}/api/v1/projects/${PROJECT}/search?q=${q}`, {
      headers,
      tags: { kind: "search", name: "search" },
    });
  } else {
    const [path, name] = pick(API_LISTS);
    res = http.get(`${API_URL}${withProject(path)}`, {
      headers,
      tags: { kind: "api", name },
    });
  }
  check(res, { "api 200": (r) => r.status === 200 });
}
