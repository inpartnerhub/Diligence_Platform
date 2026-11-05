import "server-only";

import { apiFetch } from "@/lib/api/client";
import { withQuery, type Query } from "@/lib/api/query";

import type { Page } from "@/features/admin/types";

import type { RequestItem, RequestSummary } from "./types";

const base = (projectId: string) => `/api/v1/projects/${encodeURIComponent(projectId)}/requests` as const;

export const requestsApi = {
  list: (projectId: string, query: Query = {}) => apiFetch<RequestItem[]>(withQuery(base(projectId), query)),
  page: (projectId: string, query: Query = {}) =>
    apiFetch<Page<RequestItem>>(withQuery(`${base(projectId)}/page`, query)),
  summary: (projectId: string, query: Query = {}) =>
    apiFetch<RequestSummary>(withQuery(`${base(projectId)}/summary`, query)),
};
