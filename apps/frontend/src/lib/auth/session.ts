import "server-only";

import { getIronSession, type SessionOptions } from "iron-session";
import { cookies, headers } from "next/headers";
import { redirect } from "next/navigation";
import * as client from "openid-client";

import { serverEnv } from "@/lib/config/env";

import { oidcConfiguration } from "./oidc";
import { canAccess, type NavKey } from "./permissions";
import { decodeJwtPayload, rolesFromRealmAccess } from "./roles";
import { SESSION_MAX_SECONDS, sessionTtlSeconds } from "./session-lifetime";
import {
  deleteSession,
  readSession,
  updateSession,
  type SessionData,
  type SessionUser,
} from "./session-store";

export const SESSION_COOKIE = "diligence_sid";
const REFRESH_LEEWAY_SECONDS = 30;

interface SessionCookie {
  sid?: string;
}

export function sessionCookieOptions(): SessionOptions {
  return {
    password: serverEnv.sessionSecret,
    cookieName: SESSION_COOKIE,
    ttl: SESSION_MAX_SECONDS, // matches Keycloak ssoSessionMaxLifespan
    cookieOptions: { httpOnly: true, secure: serverEnv.secureCookies, sameSite: "lax", path: "/" },
  };
}

export async function sessionCookie() {
  return getIronSession<SessionCookie>(await cookies(), sessionCookieOptions());
}

export function userFromTokens(
  accessToken: string,
  idClaims: Record<string, unknown> | undefined,
): SessionUser {
  const access = decodeJwtPayload(accessToken);
  const claims = idClaims ?? access;
  return {
    id: String(access.sub ?? claims.sub ?? ""),
    name: String(claims.name ?? claims.preferred_username ?? ""),
    email: String(claims.email ?? ""),
    roles: rolesFromRealmAccess(access.realm_access),
  };
}

/**
 * Whether the user holds at least one application role. Accounts without one (e.g. imported by an SSO broker before an
 * administrator assigned a role) get no session: the backend refuses them anyway (#152).
 */
export function hasApplicationRole(user: SessionUser): boolean {
  return user.roles.length > 0;
}

/** Current session, refreshing the access token when it is about to expire. `null` when signed out. */
export async function getSession(): Promise<SessionData | null> {
  const { sid } = await sessionCookie();
  if (!sid) {
    return null;
  }
  const session = await readSession(sid);
  if (!session) {
    return null;
  }
  if (session.expiresAt - REFRESH_LEEWAY_SECONDS > Date.now() / 1000) {
    return session;
  }
  if (!session.refreshToken) {
    return null;
  }
  let tokens: Awaited<ReturnType<typeof client.refreshTokenGrant>>;
  try {
    tokens = await client.refreshTokenGrant(oidcConfiguration(), session.refreshToken);
  } catch {
    // Refresh token expired or revoked: signed out. Drop the stored tokens too (#174).
    await deleteSession(sid).catch(() => undefined);
    return null;
  }
  const now = Math.floor(Date.now() / 1000);
  const refreshed: SessionData = {
    ...session,
    accessToken: tokens.access_token,
    refreshToken: tokens.refresh_token ?? session.refreshToken,
    idToken: tokens.id_token ?? session.idToken,
    expiresAt: now + (tokens.expires_in ?? 60),
    user: userFromTokens(tokens.access_token, tokens.claims()),
  };
  const ttl = sessionTtlSeconds(
    (tokens as Record<string, unknown>).refresh_expires_in,
    session.createdAt,
    now,
  );
  if (!hasApplicationRole(refreshed.user) || ttl <= 0) {
    await deleteSession(sid); // the role was removed meanwhile, or the maximum session age is reached
    return null;
  }
  await updateSession(sid, refreshed, ttl);
  return refreshed;
}

/**
 * For server components and actions: the session, or a redirect to the login flow that returns to the page the
 * user requested (taken from the header set by proxy.ts when not given explicitly).
 */
export async function requireSession(returnTo?: string): Promise<SessionData> {
  const session = await getSession();
  if (!session) {
    const target = safeReturnTo(returnTo ?? (await headers()).get("x-diligence-return-to"));
    redirect(`/auth/login?returnTo=${encodeURIComponent(target)}`);
  }
  return session;
}

/** Page guard: signed in and allowed to see the area; otherwise back to the home page. */
export async function requireAccess(key: NavKey): Promise<SessionData> {
  const session = await requireSession();
  if (!canAccess(session.user.roles, key)) {
    redirect("/");
  }
  return session;
}

/** Only same-origin relative paths are accepted as post-login targets (prevents open redirects). */
export function safeReturnTo(value: string | null | undefined): string {
  return value && value.startsWith("/") && !value.startsWith("//") && !value.startsWith("/\\") ? value : "/";
}
