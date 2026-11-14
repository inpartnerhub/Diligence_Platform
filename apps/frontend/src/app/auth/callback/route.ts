import { NextResponse, type NextRequest } from "next/server";
import * as client from "openid-client";

import { oidcConfiguration } from "@/lib/auth/oidc";
import { loginTransaction } from "@/lib/auth/login-transaction";
import { startSession } from "@/lib/auth/start-session";
import { serverEnv } from "@/lib/config/env";
import { logError } from "@/lib/log";

export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
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
    logError("OIDC callback failed", error);
    return NextResponse.redirect(new URL("/auth/login", serverEnv.appBaseUrl));
  }

  if (!(await startSession(tokens))) {
    // No session for an account without a role (#152); end the identity provider session so the user can sign in
    // with another account, then explain why.
    const endSession = client.buildEndSessionUrl(oidcConfiguration(), {
      client_id: serverEnv.oidcClientId,
      post_logout_redirect_uri: `${serverEnv.appBaseUrl}/auth/no-access`,
      ...(tokens.id_token && { id_token_hint: tokens.id_token }),
    });
    return NextResponse.redirect(endSession);
  }

  return NextResponse.redirect(new URL(returnTo, serverEnv.appBaseUrl));
}
