import "server-only";

import { apiFetch } from "@/lib/api/client";
import { withQuery, type Query } from "@/lib/api/query";

import type { AuditLog, Group, Organization, Page, Profile, User } from "./types";

export const adminApi = {
  users: (query: Query) => apiFetch<Page<User>>(withQuery("/api/v1/admin/users", query)),
  organizations: (query: Query = { size: 200 }) =>
    apiFetch<Page<Organization>>(withQuery("/api/v1/admin/organizations", query)),
  groups: () => apiFetch<Group[]>("/api/v1/admin/groups"),
  auditLogs: (query: Query) => apiFetch<Page<AuditLog>>(withQuery("/api/v1/admin/audit-logs", query)),
  profile: () => apiFetch<Profile>("/api/v1/me"),
};
