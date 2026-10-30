/** Global roles (PRD Bab 3). Values match the backend `Role` enum; Keycloak realm roles are the lower-case form. */
export const ROLES = [
  "SUPER_ADMIN",
  "PARTNER",
  "PROJECT_MANAGER",
  "ANALYST",
  "CLIENT",
  "TARGET",
  "OBSERVER",
] as const;
export type Role = (typeof ROLES)[number];

export const EXTERNAL_ROLES: readonly Role[] = ["CLIENT", "TARGET", "OBSERVER"];
export const MFA_ROLES: readonly Role[] = ["SUPER_ADMIN", "CLIENT", "TARGET", "OBSERVER"];

/** Extracts application roles from a Keycloak access token's `realm_access.roles` claim. */
export function rolesFromRealmAccess(claim: unknown): Role[] {
  const roles = (claim as { roles?: unknown } | undefined)?.roles;
  if (!Array.isArray(roles)) {
    return [];
  }
  return roles
    .filter((r): r is string => typeof r === "string")
    .map((r) => r.toUpperCase())
    .filter((r): r is Role => (ROLES as readonly string[]).includes(r));
}

/**
 * Reads the payload of a JWT without verifying it. Only for UI decisions (navigation); the backend verifies every
 * token and enforces authorization independently.
 */
export function decodeJwtPayload(token: string): Record<string, unknown> {
  const payload = token.split(".")[1];
  if (!payload) {
    return {};
  }
  return JSON.parse(Buffer.from(payload, "base64url").toString("utf8")) as Record<string, unknown>;
}
