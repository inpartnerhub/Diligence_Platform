import type { Role } from "@/lib/auth/roles";

// Mirrors the backend DTOs in com.inpartnerhub.diligence.project.internal; replace with schema.gen.ts types once
// `npm run generate:api` is part of the build.

export const WORKSTREAMS = [
  "FINANCIAL",
  "TAX",
  "LEGAL",
  "COMMERCIAL",
  "OPERATIONAL",
  "HR",
  "IT_CYBER",
  "ESG_COMPLIANCE",
] as const;
export type WorkstreamType = (typeof WORKSTREAMS)[number];

export const STAGES = [
  "INITIATION",
  "REQUEST_LIST",
  "DATA_COLLECTION",
  "REVIEW",
  "CLARIFICATION",
  "RISK_ASSESSMENT",
  "REPORTING",
  "CLOSED",
] as const;
export type ProjectStage = (typeof STAGES)[number];

export const PROJECT_ROLES = [
  "PARTNER",
  "PROJECT_MANAGER",
  "ANALYST",
  "CLIENT",
  "TARGET",
  "OBSERVER",
] as const;
export type ProjectRole = (typeof PROJECT_ROLES)[number];
export const MANAGER_ROLES: readonly ProjectRole[] = ["PARTNER", "PROJECT_MANAGER"];

/** Project roles a user may hold given their global role (mirrors ProjectRole.allowedFor on the backend). */
export function allowedProjectRoles(globalRole: Role): ProjectRole[] {
  switch (globalRole) {
    case "PARTNER":
    case "PROJECT_MANAGER":
    case "ANALYST":
      return ["PARTNER", "PROJECT_MANAGER", "ANALYST", "OBSERVER"];
    case "CLIENT":
      return ["CLIENT"];
    case "TARGET":
      return ["TARGET"];
    case "OBSERVER":
      return ["OBSERVER"];
    default:
      return [];
  }
}

export const RISK_LEVELS = ["CRITICAL", "HIGH", "MEDIUM", "LOW", "UNRATED"] as const;

export interface Project {
  id: string;
  code: string;
  name: string;
  clientOrganizationId: string;
  clientName: string | null;
  targetOrganizationId: string;
  targetName: string | null;
  stage: ProjectStage;
  status: "ACTIVE" | "ARCHIVED";
  startDate: string | null;
  endDate: string | null;
  templateId: string | null;
  createdAt: string;
  archivedAt: string | null;
  myRoles: ProjectRole[];
}

export interface Workstream {
  id: string;
  type: WorkstreamType;
  leadUserId: string | null;
}

export interface Member {
  id: string;
  userId: string;
  displayName: string | null;
  email: string | null;
  role: ProjectRole;
  workstreamId: string | null;
  accessExpiresAt: string | null;
  createdAt: string;
}

export interface WorkstreamProgress {
  workstreamId: string;
  type: WorkstreamType;
  total: number;
  requested: number;
  fulfilled: number;
  byStatus: Record<string, number>;
  completionPercent: number;
}

export interface Dashboard {
  projectId: string;
  stage: ProjectStage;
  status: Project["status"];
  completionPercent: number;
  workstreams: WorkstreamProgress[];
  findingsByRisk: Record<string, number>;
  dueSoon: number;
  overdue: number;
  memberCount: number;
}

export interface TemplateDefinition {
  workstreams: WorkstreamType[];
  requestItems: { workstream: WorkstreamType; category: string; description: string }[];
  findingCategories: { workstream: WorkstreamType; name: string }[];
  folders: string[];
}

export interface ProjectTemplate {
  id: string;
  name: string;
  description: string | null;
  definition: TemplateDefinition;
  createdAt: string;
}

export interface OrganizationSummary {
  id: string;
  name: string;
  type: "CONSULTANT" | "CLIENT" | "TARGET";
  active: boolean;
}

export interface UserSummary {
  id: string;
  email: string;
  displayName: string;
  organizationId: string | null;
  role: Role;
  active: boolean;
}

export interface CreateProjectInput {
  code: string;
  name: string;
  clientOrganizationId: string;
  targetOrganizationId: string;
  startDate?: string;
  endDate?: string;
  templateId?: string;
  workstreams?: WorkstreamType[];
}

export interface TemplateInput {
  name: string;
  description?: string;
  definition: TemplateDefinition;
}

export type ActionResult<T = undefined> = { ok: true; data?: T } | { ok: false; message: string };
