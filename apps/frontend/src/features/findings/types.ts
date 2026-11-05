import type { ProjectRole, WorkstreamType } from "@/features/projects/types";

// Mirrors the backend DTOs in com.inpartnerhub.diligence.finding.internal.

export const FINDING_STATUSES = ["DRAFT", "IN_REVIEW", "APPROVED", "RELEASED", "ARCHIVED"] as const;
export type FindingStatus = (typeof FINDING_STATUSES)[number];

export const LEVELS = ["CRITICAL", "HIGH", "MEDIUM", "LOW"] as const;
export type RiskLevel = (typeof LEVELS)[number];

export const SPA_PROTECTION_TYPES = [
  "PRICE_CHIP",
  "SPECIAL_INDEMNITY",
  "PRE_CLOSING_CONDITION",
  "POST_CLOSING_COVENANT",
  "WARRANTY_REPRESENTATION",
] as const;
export type SpaProtectionType = (typeof SPA_PROTECTION_TYPES)[number];

export const REMEDIATION_STATUSES = ["OPEN", "IN_PROGRESS", "MITIGATED", "WAIVED"] as const;
export type RemediationStatus = (typeof REMEDIATION_STATUSES)[number];

export const STATUS_COLORS: Record<FindingStatus, string> = {
  DRAFT: "default",
  IN_REVIEW: "processing",
  APPROVED: "cyan",
  RELEASED: "success",
  ARCHIVED: "default",
};

export const REMEDIATION_STATUS_COLORS: Record<RemediationStatus, string> = {
  OPEN: "error",
  IN_PROGRESS: "processing",
  MITIGATED: "success",
  WAIVED: "default",
};

export interface RiskMatrix {
  mediumFrom: number;
  highFrom: number;
  criticalFrom: number;
}

export interface Finding {
  id: string;
  number: string;
  workstreamId: string;
  workstream: WorkstreamType | null;
  title: string;
  category: string;
  description: string;
  impactDescription: string | null;
  monetaryImpact: number | null;
  currency: string | null;
  recommendation: string | null;
  impact: number | null;
  likelihood: number | null;
  riskScore: number | null;
  riskLevel: RiskLevel | null;
  status: FindingStatus;
  createdBy: string;
  createdByName: string | null;
  createdAt: string;
  updatedAt: string;
  submittedAt: string | null;
  approvedAt: string | null;
  approvedByName: string | null;
  releasedAt: string | null;
  releasedByName: string | null;
  archiveReason: string | null;
  documentCount: number;
  spaProtectionType: SpaProtectionType | null;
  spaClauseReference: string | null;
  remediationStatus: RemediationStatus;
  remediationAction: string | null;
  remediationDeadline: string | null;
  remediationOwner: string | null;
  remediationUpdatedAt: string | null;
  remediationUpdatedByName: string | null;
}

export interface RemediationInput {
  spaProtectionType?: SpaProtectionType | null;
  spaClauseReference?: string | null;
  remediationStatus?: RemediationStatus | null;
  remediationAction?: string | null;
  remediationDeadline?: string | null;
  remediationOwner?: string | null;
}

export interface FindingDocument {
  id: string;
  folderId: string;
  name: string;
  contentType: string;
  scanStatus: string;
  pageReference: string | null;
  note: string | null;
  linkedByName: string | null;
  linkedAt: string;
}

export interface FindingDetail {
  finding: Finding;
  documents: FindingDocument[];
  hiddenDocuments: number;
  comments: { id: string; authorId: string; authorName: string | null; body: string; createdAt: string }[];
  history: {
    fromStatus: FindingStatus | null;
    toStatus: FindingStatus;
    changedByName: string | null;
    changedAt: string;
    note: string | null;
  }[];
}

export interface FindingCategory {
  workstreamId: string;
  workstream: WorkstreamType;
  name: string;
}

export interface FindingInput {
  workstreamId: string;
  title: string;
  category: string;
  description: string;
  impactDescription?: string | null;
  monetaryImpact?: number | null;
  currency?: string | null;
  recommendation?: string | null;
  impact?: number | null;
  likelihood?: number | null;
}

/** What the caller may do with findings, from their project roles (the backend enforces the same). */
export interface FindingPermissions {
  edit: boolean;
  review: boolean;
  release: boolean;
}

export function findingPermissions(roles: readonly ProjectRole[], active: boolean): FindingPermissions {
  return {
    edit: active && roles.some((r) => ["PARTNER", "PROJECT_MANAGER", "ANALYST"].includes(r)),
    review: active && roles.some((r) => ["PARTNER", "PROJECT_MANAGER"].includes(r)),
    release: active && roles.includes("PARTNER"),
  };
}

/** Workflow moves offered in the UI; mirrors FindingService (four eyes, partner release, submit prerequisites). */
export function nextStatuses(
  finding: Pick<Finding, "status" | "riskLevel" | "documentCount" | "createdBy">,
  permissions: FindingPermissions,
  userId: string,
): FindingStatus[] {
  if (!permissions.edit) return [];
  switch (finding.status) {
    case "DRAFT":
      return [
        ...(finding.riskLevel !== null && finding.documentCount > 0 ? (["IN_REVIEW"] as const) : []),
        ...(permissions.review ? (["ARCHIVED"] as const) : []),
      ];
    case "IN_REVIEW":
      if (!permissions.review) return [];
      return [...(finding.createdBy !== userId ? (["APPROVED"] as const) : []), "DRAFT", "ARCHIVED"];
    case "APPROVED":
      if (!permissions.review) return [];
      return [...(permissions.release ? (["RELEASED"] as const) : []), "DRAFT", "ARCHIVED"];
    case "RELEASED":
      return permissions.release ? ["ARCHIVED"] : [];
    default:
      return [];
  }
}

/** Moves that need a reason: returning or reopening to draft and archiving. */
export function needsReason(from: FindingStatus, to: FindingStatus): boolean {
  return to === "ARCHIVED" || (to === "DRAFT" && from !== "DRAFT");
}

/** Level of an impact × likelihood score under the configured thresholds (mirrors RiskMatrix.rate). */
export function levelFor(score: number, matrix: RiskMatrix): RiskLevel {
  if (score >= matrix.criticalFrom) return "CRITICAL";
  if (score >= matrix.highFrom) return "HIGH";
  return score >= matrix.mediumFrom ? "MEDIUM" : "LOW";
}

/** Finding counts per heat-map cell, keyed "impact:likelihood" (archived findings are left out). */
/**
 * Aggregates of the filtered register computed by the backend (archived findings excluded); sourcedPercent is the
 * share citing at least one source document — PRD KPI target ≥ 95%.
 */
export interface FindingSummary {
  total: number;
  levels: Partial<Record<RiskLevel, number>>;
  cells: { impact: number; likelihood: number; count: number }[];
  sourcedPercent: number | null;
}

/** Heat-map cells keyed "impact:likelihood", from the backend summary. */
export function heatMapFromSummary(summary: Pick<FindingSummary, "cells">): Map<string, number> {
  const cells = new Map<string, number>();
  for (const cell of summary.cells) {
    const key = `${cell.impact}:${cell.likelihood}`;
    cells.set(key, (cells.get(key) ?? 0) + cell.count);
  }
  return cells;
}

export interface FindingLibraryTemplate {
  id: string;
  workstreamType: WorkstreamType;
  category: string;
  title: string;
  standardDescription: string;
  defaultRecommendation?: string | null;
  defaultImpact: number;
  defaultLikelihood: number;
  createdAt: string;
  updatedAt: string;
}

export interface FindingLibraryTemplateInput {
  workstreamType: WorkstreamType;
  category: string;
  title: string;
  standardDescription: string;
  defaultRecommendation?: string | null;
  defaultImpact: number;
  defaultLikelihood: number;
}
