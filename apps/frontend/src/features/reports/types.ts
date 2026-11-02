import type { ProjectRole } from "@/features/projects/types";

// Mirrors the backend DTOs in com.inpartnerhub.diligence.report.internal.

export type ReportStatus =
  | "DRAFT"
  | "SUBMITTED_FOR_REVIEW"
  | "APPROVED_BY_MANAGER"
  | "RELEASED_BY_PARTNER"
  | "IN_REVIEW"
  | "APPROVED"
  | "ISSUED";

export interface ReportStatusHistoryItem {
  id: string;
  fromStatus: string | null;
  toStatus: string;
  changedByName: string | null;
  changedAt: string;
  note: string | null;
}

export interface Report {
  id: string;
  versionNumber: number;
  majorVersion?: number;
  minorVersion?: number;
  versionLabel?: string;
  title: string;
  status: ReportStatus;
  language: "id" | "en";
  findingCount: number;
  sizeBytes: number | null;
  createdByName: string | null;
  createdAt: string;
  submittedByName?: string | null;
  submittedAt?: string | null;
  managerApprovedByName?: string | null;
  managerApprovedAt?: string | null;
  issuedByName: string | null;
  issuedAt: string | null;
}

export interface ReportPermissions {
  generate: boolean;
  submit: boolean;
  approve: boolean;
  release: boolean;
  returnDraft: boolean;
  issue: boolean;
  word: boolean;
}

/** Generating: partners and PMs; review workflow follows tiered roles; Word files: internal team. */
export function reportPermissions(roles: readonly ProjectRole[], active: boolean): ReportPermissions {
  const isPartner = roles.includes("PARTNER");
  const isPM = roles.includes("PROJECT_MANAGER");
  const isAnalyst = roles.includes("ANALYST");
  const isObserver = roles.includes("OBSERVER");
  const isTeam = isPartner || isPM || isAnalyst || isObserver;

  return {
    generate: active && (isPartner || isPM),
    submit: active && (isPartner || isPM || isAnalyst),
    approve: active && (isPartner || isPM),
    release: active && isPartner,
    returnDraft: active && (isPartner || isPM),
    issue: active && isPartner,
    word: isTeam,
  };
}
