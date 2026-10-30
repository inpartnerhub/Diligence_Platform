import type { ProjectRole } from "@/features/projects/types";

// Mirrors the backend DTOs in com.inpartnerhub.diligence.report.internal.

export type ReportStatus = "DRAFT" | "IN_REVIEW" | "APPROVED" | "ISSUED";

export interface Report {
  id: string;
  versionNumber: number;
  title: string;
  status: ReportStatus;
  language: "id" | "en";
  findingCount: number;
  sizeBytes: number | null;
  createdByName: string | null;
  createdAt: string;
  issuedByName: string | null;
  issuedAt: string | null;
}

export interface ReportPermissions {
  generate: boolean;
  issue: boolean;
  word: boolean;
}

/** Generating: partners and PMs; issuing: partners; Word files: the internal team (the client gets PDF only). */
export function reportPermissions(roles: readonly ProjectRole[], active: boolean): ReportPermissions {
  return {
    generate: active && roles.some((r) => ["PARTNER", "PROJECT_MANAGER"].includes(r)),
    issue: active && roles.includes("PARTNER"),
    word: roles.some((r) => ["PARTNER", "PROJECT_MANAGER", "ANALYST", "OBSERVER"].includes(r)),
  };
}
