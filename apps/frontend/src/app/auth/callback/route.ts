import { randomUUID } from "node:crypto";

import { cookies } from "next/headers";
import { NextResponse, type NextRequest } from "next/server";
import * as client from "openid-client";

import { LOCALE_COOKIE, toLocale } from "@/i18n/locales";
import { oidcConfiguration } from "@/lib/auth/oidc";
import { loginTransaction } from "@/lib/auth/login-transaction";
import { sessionCookie, userFromTokens } from "@/lib/auth/session";
import { writeSession } from "@/lib/auth/session-store";
import { serverEnv } from "@/lib/config/env";

export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  const store = await cookies();
  const tx = await loginTransaction();
  const { codeVerifier, state, returnTo = "/" } = tx;
  tx.destroy();
  if (!codeVerifier || !state) {
    return NextResponse.redirect(new URL("/auth/login", serverEnv.appBaseUrl));
  }

  // Rebuild the callback URL on the public origin: behind a proxy request.url carries the internal host.
  const currentUrl = new URL(`${serverEnv.appBaseUrl}/auth/callback${request.nextUrl.search}`);
  let tokens: Awaited<ReturnType<typeof client.authorizationCodeGrant>>;
  try {
    tokens = await client.authorizationCodeGrant(oidcConfiguration(), currentUrl, {
      pkceCodeVerifier: codeVerifier,
      expectedState: state,
    });
  } catch (error) {
    console.error("OIDC callback failed", error);
    return NextResponse.redirect(new URL("/auth/login", serverEnv.appBaseUrl));
  }

  const sessionId = randomUUID();
  const user = userFromTokens(tokens.access_token, tokens.claims());
  const refreshLifetime = Number((tokens as Record<string, unknown>).refresh_expires_in ?? 1800);
  await writeSession(
    sessionId,
    {
      accessToken: tokens.access_token,
      refreshToken: tokens.refresh_token,
      idToken: tokens.id_token,
      expiresAt: Math.floor(Date.now() / 1000) + (tokens.expires_in ?? 60),
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
      store.set(LOCALE_COOKIE, toLocale(locale), { path: "/", sameSite: "lax", maxAge: 60 * 60 * 24 * 365 });
    }
  } catch {
    // Non-critical: keep the current language.
  }

  return NextResponse.redirect(new URL(returnTo, serverEnv.appBaseUrl));
}
