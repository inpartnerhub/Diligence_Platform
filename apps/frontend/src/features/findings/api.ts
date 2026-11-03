import "server-only";

import { apiFetch } from "@/lib/api/client";
import { withQuery, type Query } from "@/lib/api/query";

import type { Finding, FindingCategory, FindingLibraryTemplate, RiskMatrix } from "./types";

const base = (projectId: string) => `/api/v1/projects/${encodeURIComponent(projectId)}/findings` as const;

export const findingsApi = {
  list: (projectId: string, query: Query = {}) => apiFetch<Finding[]>(withQuery(base(projectId), query)),
  categories: (projectId: string) => apiFetch<FindingCategory[]>(`${base(projectId)}/categories`),
  matrix: () => apiFetch<RiskMatrix>("/api/v1/risk-matrix"),
  libraryTemplates: (query: Query = {}) =>
    apiFetch<FindingLibraryTemplate[]>(withQuery("/api/v1/finding-library", query)),
};
