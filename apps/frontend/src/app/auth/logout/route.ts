import { NextResponse, type NextRequest } from "next/server";
import * as client from "openid-client";

import { oidcConfiguration } from "@/lib/auth/oidc";
import { sessionCookie } from "@/lib/auth/session";
import { deleteSession, readSession } from "@/lib/auth/session-store";
import { serverEnv } from "@/lib/config/env";

export const dynamic = "force-dynamic";

/** POST only (from the user menu form) so third-party pages cannot sign users out with a link. */
export async function POST(request: NextRequest) {
  const origin = request.headers.get("origin");
  if (origin && origin !== new URL(serverEnv.appBaseUrl).origin) {
    return new NextResponse(null, { status: 403 });
  }
  const cookie = await sessionCookie();
  const sid = cookie.sid;
  const session = sid ? await readSession(sid) : null;
  if (sid) {
    await deleteSession(sid);
  }
  cookie.destroy();

  const endSession = client.buildEndSessionUrl(oidcConfiguration(), {
    client_id: serverEnv.oidcClientId,
    post_logout_redirect_uri: `${serverEnv.appBaseUrl}/`,
    ...(session?.idToken && { id_token_hint: session.idToken }),
  });
  return NextResponse.redirect(endSession, { status: 303 });
}
