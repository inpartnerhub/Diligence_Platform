import "server-only";

import { ApiError, apiFetch } from "@/lib/api/client";
import { withQuery, type Query } from "@/lib/api/query";

import type {
  AuditLog,
  Group,
  Organization,
  Page,
  Profile,
  SearchReindexRun,
  User,
  WebhookDeliveryLog,
  WebhookSubscription,
} from "./types";

export const adminApi = {
  users: (query: Query) => apiFetch<Page<User>>(withQuery("/api/v1/admin/users", query)),
  organizations: (query: Query = { size: 200 }) =>
    apiFetch<Page<Organization>>(withQuery("/api/v1/admin/organizations", query)),
  groups: () => apiFetch<Group[]>("/api/v1/admin/groups"),
  auditLogs: (query: Query) => apiFetch<Page<AuditLog>>(withQuery("/api/v1/admin/audit-logs", query)),
  profile: () => apiFetch<Profile>("/api/v1/me"),
  webhooks: () => apiFetch<WebhookSubscription[]>("/api/v1/admin/webhooks"),
  webhookDeliveries: (id: string) =>
    apiFetch<WebhookDeliveryLog[]>(`/api/v1/admin/webhooks/${encodeURIComponent(id)}/deliveries`),
  /** The latest search re-index run, or null before the first one (404). */
  searchReindex: async () => {
    try {
      return await apiFetch<SearchReindexRun>("/api/v1/admin/search/reindex");
    } catch (error) {
      if (error instanceof ApiError && error.problem.status === 404) return null;
      throw error;
    }
  },
};
