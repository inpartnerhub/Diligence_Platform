import "server-only";

import { apiFetch } from "@/lib/api/client";
import { withQuery, type Query } from "@/lib/api/query";

import type { Page } from "@/features/admin/types";

import type { Finding, FindingCategory, FindingLibraryTemplate, FindingSummary, RiskMatrix } from "./types";

const base = (projectId: string) => `/api/v1/projects/${encodeURIComponent(projectId)}/findings` as const;

export const findingsApi = {
  list: (projectId: string, query: Query = {}) => apiFetch<Finding[]>(withQuery(base(projectId), query)),
  page: (projectId: string, query: Query = {}) =>
    apiFetch<Page<Finding>>(withQuery(`${base(projectId)}/page`, query)),
  summary: (projectId: string, query: Query = {}) =>
    apiFetch<FindingSummary>(withQuery(`${base(projectId)}/summary`, query)),
  categories: (projectId: string) => apiFetch<FindingCategory[]>(`${base(projectId)}/categories`),
  matrix: () => apiFetch<RiskMatrix>("/api/v1/risk-matrix"),
  libraryTemplates: (query: Query = {}) =>
    apiFetch<FindingLibraryTemplate[]>(withQuery("/api/v1/finding-library", query)),
};
