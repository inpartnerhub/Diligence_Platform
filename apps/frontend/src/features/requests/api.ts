import "server-only";

import { apiFetch } from "@/lib/api/client";
import { withQuery, type Query } from "@/lib/api/query";

import type { RequestItem } from "./types";

export const requestsApi = {
  list: (projectId: string, query: Query = {}) =>
    apiFetch<RequestItem[]>(withQuery(`/api/v1/projects/${encodeURIComponent(projectId)}/requests`, query)),
};
