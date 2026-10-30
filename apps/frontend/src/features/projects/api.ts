import "server-only";

import type { Page } from "@/features/admin/types";
import { apiFetch } from "@/lib/api/client";
import { withQuery, type Query } from "@/lib/api/query";

import type { Dashboard, Member, OrganizationSummary, Project, ProjectTemplate, Workstream } from "./types";

export const projectsApi = {
  list: (query: Query) => apiFetch<Page<Project>>(withQuery("/api/v1/projects", query)),
  get: (id: string) => apiFetch<Project>(`/api/v1/projects/${encodeURIComponent(id)}`),
  workstreams: (id: string) =>
    apiFetch<Workstream[]>(`/api/v1/projects/${encodeURIComponent(id)}/workstreams`),
  members: (id: string) => apiFetch<Member[]>(`/api/v1/projects/${encodeURIComponent(id)}/members`),
  dashboard: (id: string) => apiFetch<Dashboard>(`/api/v1/projects/${encodeURIComponent(id)}/dashboard`),
  templates: () => apiFetch<ProjectTemplate[]>("/api/v1/project-templates"),
  organizations: (type: OrganizationSummary["type"]) =>
    apiFetch<OrganizationSummary[]>(withQuery("/api/v1/directory/organizations", { type })),
};
