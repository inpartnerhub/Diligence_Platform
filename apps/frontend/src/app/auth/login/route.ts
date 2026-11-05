import { cookies } from "next/headers";
import { NextResponse, type NextRequest } from "next/server";
import * as client from "openid-client";

import { LOCALE_COOKIE, toLocale } from "@/i18n/locales";
import { callbackUrl, oidcConfiguration } from "@/lib/auth/oidc";
import { loginTransaction } from "@/lib/auth/login-transaction";
import { safeReturnTo } from "@/lib/auth/session";

export const dynamic = "force-dynamic";

/**
 * Starts Authorization Code + PKCE. The verifier and state live in a short-lived sealed cookie. With `stepUp=1` the
 * identity provider must authenticate the user again (password + MFA) instead of reusing the SSO session, which gives
 * the new tokens a fresh `auth_time` for step-up checks (#62).
 */
export async function GET(request: NextRequest) {
  const codeVerifier = client.randomPKCECodeVerifier();
  const state = client.randomState();
  const store = await cookies();
  const tx = await loginTransaction();
  tx.codeVerifier = codeVerifier;
  tx.state = state;
  tx.returnTo = safeReturnTo(request.nextUrl.searchParams.get("returnTo"));
  await tx.save();

  const authorizationUrl = client.buildAuthorizationUrl(oidcConfiguration(), {
    redirect_uri: callbackUrl(),
    scope: "openid profile email",
    code_challenge: await client.calculatePKCECodeChallenge(codeVerifier),
    code_challenge_method: "S256",
    state,
    ui_locales: toLocale(store.get(LOCALE_COOKIE)?.value),
    ...(request.nextUrl.searchParams.get("stepUp") === "1" ? { prompt: "login", max_age: "0" } : {}),
  });
  return NextResponse.redirect(authorizationUrl);
}
