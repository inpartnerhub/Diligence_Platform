import "server-only";

import { apiFetch } from "@/lib/api/client";
import { withQuery, type Query } from "@/lib/api/query";

import type { Question } from "./types";

export const qnaApi = {
  list: (projectId: string, query: Query = {}) =>
    apiFetch<Question[]>(withQuery(`/api/v1/projects/${encodeURIComponent(projectId)}/questions`, query)),
};
