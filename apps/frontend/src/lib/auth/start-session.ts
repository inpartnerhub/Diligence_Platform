import "server-only";

import { randomUUID } from "node:crypto";

import { cookies } from "next/headers";
import type * as client from "openid-client";

import { LOCALE_COOKIE, toLocale } from "@/i18n/locales";
import { serverEnv } from "@/lib/config/env";

import { hasApplicationRole, sessionCookie, userFromTokens } from "./session";
import { sessionTtlSeconds } from "./session-lifetime";
import { writeSession } from "./session-store";

type Tokens = Awaited<ReturnType<typeof client.authorizationCodeGrant>>;

/**
 * Creates the server-side session for freshly issued tokens and sets the session cookie. Returns false, without a
 * session, for an account without an application role (#152). Shared by the OIDC callback and the demo sign-in.
 */
export async function startSession(tokens: Tokens): Promise<boolean> {
  const user = userFromTokens(tokens.access_token, tokens.claims());
  if (!hasApplicationRole(user)) {
    return false;
  }

  const sessionId = randomUUID();
  const now = Math.floor(Date.now() / 1000);
  const refreshLifetime = sessionTtlSeconds((tokens as Record<string, unknown>).refresh_expires_in, now, now);
  await writeSession(
    sessionId,
    {
      accessToken: tokens.access_token,
      refreshToken: tokens.refresh_token,
      idToken: tokens.id_token,
      expiresAt: now + (tokens.expires_in ?? 60),
      createdAt: now,
      user,
    },
    refreshLifetime,
  );
  const session = await sessionCookie();
  session.sid = sessionId;
  await session.save();

  // Adopt the language stored in the user's profile, if the backend knows it.
  try {
    const profile = await fetch(new URL("/api/v1/me", serverEnv.apiBaseUrl), {
      headers: { Authorization: `Bearer ${tokens.access_token}`, Accept: "application/json" },
      cache: "no-store",
    });
    if (profile.ok) {
      const { locale } = (await profile.json()) as { locale?: string };
      (await cookies()).set(LOCALE_COOKIE, toLocale(locale), {
        path: "/",
        sameSite: "lax",
        maxAge: 60 * 60 * 24 * 365,
      });
    }
  } catch {
    // Non-critical: keep the current language.
  }
  return true;
}
