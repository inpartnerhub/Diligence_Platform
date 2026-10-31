import { NextResponse, type NextRequest } from "next/server";

import { contentSecurityPolicy } from "@/lib/security/csp";

/**
 * Optimistic check only: no session cookie → start the login flow. The session itself is validated (and tokens
 * refreshed) server-side in the app layout; the backend re-validates every access token. Every response rendered
 * here, including the not-found page for file-like paths such as /robots.txt, gets a Content-Security-Policy with
 * a fresh script nonce.
 */
// Read by requireSession (src/lib/auth/session.ts); keep the names in sync.
const RETURN_TO_HEADER = "x-diligence-return-to";

const FILE_PATH = /\.[a-z0-9]+$/;

export function proxy(request: NextRequest) {
  const returnTo = request.nextUrl.pathname + request.nextUrl.search;
  const signedIn = request.cookies.has("diligence_sid");
  if (!signedIn && !FILE_PATH.test(request.nextUrl.pathname)) {
    const login = new URL("/auth/login", request.url);
    login.searchParams.set("returnTo", returnTo);
    return NextResponse.redirect(login);
  }
  const policy = contentSecurityPolicy({
    nonce: Buffer.from(crypto.randomUUID()).toString("base64"),
    development: process.env.NODE_ENV === "development",
    issuer: process.env.OIDC_ISSUER,
    sentryDsn: process.env.NEXT_PUBLIC_SENTRY_DSN,
  });
  // Next.js reads the nonce from the request's CSP. The session may still turn out to be expired server-side; pass
  // the requested path along so the login redirect can bring the user back here (see requireSession).
  const headers = new Headers(request.headers);
  headers.set("Content-Security-Policy", policy);
  if (signedIn) headers.set(RETURN_TO_HEADER, returnTo);
  const response = NextResponse.next({ request: { headers } });
  response.headers.set("Content-Security-Policy", policy);
  return response;
}

export const config = {
  // Everything except auth routes, API route handlers (they check the session themselves and must stream large
  // uploads, which the proxy would buffer) and Next.js build assets.
  matcher: ["/((?!auth/|api/|_next/static/|_next/image|favicon.ico).*)"],
};
