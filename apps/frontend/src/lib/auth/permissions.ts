import type { Role } from "./roles";

/**
 * Navigation and page access per role (PRD Bab 3 and Bab 11.1). This is UX only: the backend and PostgreSQL
 * row-level security enforce the same rules on data (e.g. the target side never sees findings).
 */
export type NavKey =
  "projects" | "dashboard" | "requests" | "dataRoom" | "findings" | "qna" | "reports" | "admin";

const CONSULTANTS: readonly Role[] = ["PARTNER", "PROJECT_MANAGER", "ANALYST", "OBSERVER"];

export const NAV_ACCESS: Record<NavKey, readonly Role[]> = {
  projects: ["SUPER_ADMIN", ...CONSULTANTS, "CLIENT", "TARGET"],
  dashboard: [...CONSULTANTS, "CLIENT"],
  requests: [...CONSULTANTS, "TARGET"],
  dataRoom: [...CONSULTANTS, "TARGET"],
  findings: [...CONSULTANTS, "CLIENT"], // client: released findings only; target: never
  qna: [...CONSULTANTS, "CLIENT", "TARGET"],
  reports: [...CONSULTANTS, "CLIENT"],
  admin: ["SUPER_ADMIN"],
};

export function canAccess(roles: readonly Role[], key: NavKey): boolean {
  return roles.some((role) => NAV_ACCESS[key].includes(role));
}

export function accessibleNav(roles: readonly Role[]): NavKey[] {
  return (Object.keys(NAV_ACCESS) as NavKey[]).filter((key) => canAccess(roles, key));
}
