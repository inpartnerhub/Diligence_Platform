import "server-only";

import { apiFetch } from "@/lib/api/client";

import type { Report } from "./types";

export const reportsApi = {
  list: (projectId: string) =>
    apiFetch<Report[]>(`/api/v1/projects/${encodeURIComponent(projectId)}/reports`),
};
