import type { Role } from "@/lib/auth/roles";

// Mirrors the backend DTOs in com.inpartnerhub.diligence.admin.internal; replace with schema.gen.ts types once
// `npm run generate:api` is part of the build.

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export type UserStatus = "INVITED" | "ACTIVE" | "DISABLED";
export type OrganizationType = "CONSULTANT" | "CLIENT" | "TARGET";
export type OrganizationStatus = "ACTIVE" | "INACTIVE";

export interface User {
  id: string;
  email: string;
  displayName: string;
  organizationId: string | null;
  role: Role;
  locale: "id" | "en";
  status: UserStatus;
  mfaEnabled: boolean;
  lastLoginAt: string | null;
  createdAt: string;
}

export interface Organization {
  id: string;
  name: string;
  type: OrganizationType;
  status: OrganizationStatus;
  defaultRetentionDays: number;
  createdAt: string;
}

export interface CreateOrganizationInput {
  name: string;
  type: OrganizationType;
  defaultRetentionDays?: number;
}

export interface UpdateOrganizationInput {
  name?: string;
  status?: OrganizationStatus;
  defaultRetentionDays?: number;
}

export interface Group {
  id: string;
  name: string;
  description: string | null;
  organizationId: string | null;
  memberIds: string[];
}

export interface AuditLog {
  id: string;
  occurredAt: string;
  userId: string | null;
  action: string;
  objectType: string;
  objectId: string | null;
  ipAddress: string | null;
  userAgent: string | null;
  outcome: string;
  details: string;
}

export interface Profile {
  id: string;
  email: string;
  displayName: string;
  roles: Role[];
  organization: { id: string; name: string; type: OrganizationType } | null;
  locale: "id" | "en";
  mfaEnabled: boolean;
  lastLoginAt: string | null;
}

export interface InviteUserInput {
  email: string;
  firstName: string;
  lastName: string;
  organizationId?: string;
  role: Role;
  locale: "id" | "en";
}

export interface WebhookSubscription {
  id: string;
  organizationId: string | null;
  targetUrl: string;
  /** Only present right after the key was generated (create, rotate); shown once. */
  signingKey: string | null;
  signingKeyHint: string;
  description: string | null;
  subscribedEvents: string;
  active: boolean;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface WebhookSubscriptionInput {
  organizationId?: string | null;
  targetUrl: string;
  signingKey?: string | null;
  description?: string | null;
  subscribedEvents?: string;
  active?: boolean;
}

export interface WebhookDeliveryLog {
  id: string;
  subscriptionId: string;
  eventType: string;
  payload: string;
  statusCode: number | null;
  success: boolean;
  attemptCount: number;
  errorMessage: string | null;
  deliveredAt: string;
  durationMs: number | null;
}

export interface WebhookTestResult {
  success: boolean;
  statusCode: number | null;
  durationMs: number;
  errorMessage: string | null;
  logId: string;
}

/** `stepUp`: the action needs a fresh sign-in with MFA first (#65). */
export type ActionResult<T = undefined> =
  { ok: true; data?: T } | { ok: false; message: string; stepUp?: true };

/** Bulk re-index of the search index from PostgreSQL (#80); one run at a time. */
export interface SearchReindexRun {
  id: string;
  status: "RUNNING" | "COMPLETED" | "FAILED";
  requestedBy: string;
  startedAt: string;
  heartbeatAt: string;
  finishedAt: string | null;
  documentsTotal: number;
  indexed: number;
  failed: number;
  /** Stale entries removed after the run; null when the sweep was skipped (some documents failed) or not reached. */
  removed: number | null;
  error: string | null;
}

/** Share of documents processed so far, 0–100. */
export function reindexPercent(run: Pick<SearchReindexRun, "documentsTotal" | "indexed" | "failed">): number {
  if (run.documentsTotal <= 0) return 0;
  return Math.min(100, Math.floor(((run.indexed + run.failed) / run.documentsTotal) * 100));
}
