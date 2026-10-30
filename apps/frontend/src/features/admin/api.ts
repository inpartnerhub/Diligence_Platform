import "server-only";

import { apiFetch } from "@/lib/api/client";

import type { AuditLog, Group, Organization, Page, Profile, User } from "./types";

type Query = Record<string, string | number | undefined>;

function withQuery(path: string, query: Query): `/api/${string}` {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== "") {
      params.set(key, String(value));
    }
  }
  const qs = params.toString();
  return `${path}${qs ? `?${qs}` : ""}` as `/api/${string}`;
}

export const adminApi = {
  users: (query: Query) => apiFetch<Page<User>>(withQuery("/api/v1/admin/users", query)),
  organizations: (query: Query = { size: 200 }) =>
    apiFetch<Page<Organization>>(withQuery("/api/v1/admin/organizations", query)),
  groups: () => apiFetch<Group[]>("/api/v1/admin/groups"),
  auditLogs: (query: Query) => apiFetch<Page<AuditLog>>(withQuery("/api/v1/admin/audit-logs", query)),
  profile: () => apiFetch<Profile>("/api/v1/me"),
};
