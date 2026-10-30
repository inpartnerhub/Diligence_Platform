import type { ProjectRole, WorkstreamType } from "@/features/projects/types";

// Mirrors the backend DTOs in com.inpartnerhub.diligence.request.internal.

export const REQUEST_STATUSES = [
  "NOT_REQUESTED",
  "REQUESTED",
  "PARTIAL",
  "RECEIVED",
  "REJECTED",
  "CLOSED",
] as const;
export type RequestStatus = (typeof REQUEST_STATUSES)[number];

export const PRIORITIES = ["LOW", "MEDIUM", "HIGH"] as const;
export type RequestPriority = (typeof PRIORITIES)[number];

export const STATUS_COLORS: Record<RequestStatus, string> = {
  NOT_REQUESTED: "default",
  REQUESTED: "processing",
  PARTIAL: "warning",
  RECEIVED: "cyan",
  REJECTED: "error",
  CLOSED: "success",
};

export const PRIORITY_COLORS: Record<RequestPriority, string> = {
  LOW: "default",
  MEDIUM: "blue",
  HIGH: "red",
};

/** Statuses still waiting for the target (count as due soon / overdue). */
export const OPEN_STATUSES: readonly RequestStatus[] = ["REQUESTED", "PARTIAL", "REJECTED"];

/** Mirrors RequestStatus.internalMayMoveTo on the backend. */
const INTERNAL_TRANSITIONS: Record<RequestStatus, readonly RequestStatus[]> = {
  NOT_REQUESTED: ["REQUESTED", "CLOSED"],
  REQUESTED: ["NOT_REQUESTED", "PARTIAL", "RECEIVED", "CLOSED"],
  PARTIAL: ["REQUESTED", "RECEIVED", "REJECTED", "CLOSED"],
  RECEIVED: ["PARTIAL", "REJECTED", "CLOSED"],
  REJECTED: ["REQUESTED", "PARTIAL", "RECEIVED", "CLOSED"],
  CLOSED: ["REQUESTED", "RECEIVED"],
};

export interface RequestItem {
  id: string;
  number: string;
  workstreamId: string;
  workstream: WorkstreamType | null;
  category: string;
  description: string;
  priority: RequestPriority;
  dueDate: string | null;
  status: RequestStatus;
  assigneeUserId: string | null;
  assigneeName: string | null;
  wave: number;
  requestedAt: string | null;
  updatedAt: string;
  documentCount: number;
}

export interface LinkedDocument {
  id: string;
  folderId: string;
  name: string;
  contentType: string;
  sizeBytes: number;
  scanStatus: "PENDING" | "CLEAN" | "INFECTED" | "REJECTED" | "FAILED";
  comment: string | null;
  linkedBy: string;
  linkedByName: string | null;
  linkedAt: string;
}

export interface RequestComment {
  id: string;
  authorId: string;
  authorName: string | null;
  body: string;
  internal: boolean;
  createdAt: string;
}

export interface StatusChange {
  fromStatus: RequestStatus | null;
  toStatus: RequestStatus;
  wave: number;
  changedBy: string | null;
  changedByName: string | null;
  changedAt: string;
  note: string | null;
}

export interface RequestItemDetail {
  item: RequestItem;
  documents: LinkedDocument[];
  hiddenDocuments: number;
  comments: RequestComment[];
  history: StatusChange[];
}

export interface ItemInput {
  workstreamId: string;
  number?: string;
  category?: string;
  description: string;
  priority?: RequestPriority;
  dueDate?: string | null;
  wave?: number;
  assigneeUserId?: string | null;
}

export interface ImportRowResult {
  row: number;
  number: string | null;
  action: "CREATE" | "UPDATE";
  errors: string[];
}

export interface ImportResult {
  committed: boolean;
  created: number;
  updated: number;
  invalid: number;
  rows: ImportRowResult[];
}

/** What the caller may do on the request list, from their project roles (the backend enforces the same). */
export interface RequestPermissions {
  edit: boolean;
  release: boolean;
  deliver: boolean;
}

export function requestPermissions(roles: readonly ProjectRole[], active: boolean): RequestPermissions {
  const internal = roles.some((r) => ["PARTNER", "PROJECT_MANAGER", "ANALYST", "OBSERVER"].includes(r));
  return {
    edit: active && roles.some((r) => ["PARTNER", "PROJECT_MANAGER", "ANALYST"].includes(r)),
    release: active && roles.some((r) => ["PARTNER", "PROJECT_MANAGER"].includes(r)),
    deliver: active && !internal && roles.includes("TARGET"),
  };
}

/** Status changes offered in the UI: the internal workflow, or delivery reports for the target. */
export function nextStatuses(
  status: RequestStatus,
  permissions: RequestPermissions,
  hasDocuments: boolean,
): RequestStatus[] {
  if (permissions.deliver) {
    return OPEN_STATUSES.includes(status) && hasDocuments ? ["PARTIAL", "RECEIVED"] : [];
  }
  if (!permissions.edit) return [];
  return INTERNAL_TRANSITIONS[status].filter(
    (next) =>
      (next !== "NOT_REQUESTED" || !hasDocuments) &&
      (status !== "NOT_REQUESTED" || next !== "REQUESTED" || permissions.release),
  );
}

/** Open items past their due date (dates as YYYY-MM-DD, compared in the user's calendar). */
export function isOverdue(item: Pick<RequestItem, "status" | "dueDate">, today: string): boolean {
  return item.dueDate !== null && OPEN_STATUSES.includes(item.status) && item.dueDate < today;
}

export function waves(items: readonly Pick<RequestItem, "wave">[]): number[] {
  return [...new Set(items.map((i) => i.wave))].sort((a, b) => a - b);
}

/** Unreleased items per wave, for the release dialog. */
export function unreleasedByWave(
  items: readonly Pick<RequestItem, "wave" | "status">[],
): Map<number, number> {
  const counts = new Map<number, number>();
  for (const item of items) {
    if (item.status === "NOT_REQUESTED") counts.set(item.wave, (counts.get(item.wave) ?? 0) + 1);
  }
  return new Map([...counts.entries()].sort(([a], [b]) => a - b));
}
