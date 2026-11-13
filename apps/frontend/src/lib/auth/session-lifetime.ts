/** Matches Keycloak's ssoSessionMaxLifespan and the session cookie's TTL (session.ts). */
export const SESSION_MAX_SECONDS = 10 * 60 * 60;
/** Keycloak's ssoSessionIdleTimeout, used when a token response carries no refresh_expires_in. */
export const DEFAULT_REFRESH_SECONDS = 30 * 60;

/**
 * How long the server-side session may live after a token (re)issue (#174): as long as the refresh token that came with
 * it (Keycloak extends it on every refresh while the user is active, up to the SSO session's maximum), never beyond
 * the maximum session age counted from the sign-in. 0 means the session is over.
 */
export function sessionTtlSeconds(
  refreshExpiresIn: unknown,
  createdAt: number | undefined,
  now: number,
): number {
  const refresh = Number(refreshExpiresIn);
  const ttl = Number.isFinite(refresh) && refresh > 0 ? Math.floor(refresh) : DEFAULT_REFRESH_SECONDS;
  if (createdAt === undefined) {
    return ttl;
  }
  return Math.max(0, Math.min(ttl, createdAt + SESSION_MAX_SECONDS - now));
}
