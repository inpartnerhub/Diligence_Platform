import "server-only";

import { apiFetch } from "@/lib/api/client";
import { withQuery, type Query } from "@/lib/api/query";

import type { Page } from "@/features/admin/types";

import type { Question, QuestionSummary } from "./types";

const base = (projectId: string) => `/api/v1/projects/${encodeURIComponent(projectId)}/questions` as const;

export const qnaApi = {
  list: (projectId: string, query: Query = {}) => apiFetch<Question[]>(withQuery(base(projectId), query)),
  page: (projectId: string, query: Query = {}) =>
    apiFetch<Page<Question>>(withQuery(`${base(projectId)}/page`, query)),
  summary: (projectId: string, query: Query = {}) =>
    apiFetch<QuestionSummary>(withQuery(`${base(projectId)}/summary`, query)),
};
