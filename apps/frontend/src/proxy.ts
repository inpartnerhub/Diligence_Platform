import { NextResponse, type NextRequest } from "next/server";

/**
 * Optimistic check only: no session cookie → start the login flow. The session itself is validated (and tokens
 * refreshed) server-side in the app layout; the backend re-validates every access token.
 */
// Read by requireSession (src/lib/auth/session.ts); keep the names in sync.
const RETURN_TO_HEADER = "x-diligence-return-to";

export function proxy(request: NextRequest) {
  const returnTo = request.nextUrl.pathname + request.nextUrl.search;
  if (request.cookies.has("diligence_sid")) {
    // The session may still turn out to be expired server-side; pass the requested path along so the login
    // redirect can bring the user back here (see requireSession).
    const headers = new Headers(request.headers);
    headers.set(RETURN_TO_HEADER, returnTo);
    return NextResponse.next({ request: { headers } });
  }
  const login = new URL("/auth/login", request.url);
  login.searchParams.set("returnTo", returnTo);
  return NextResponse.redirect(login);
}

export const config = {
  // Everything except auth routes, API route handlers (they check the session themselves and must stream large
  // uploads, which the proxy would buffer), Next.js assets and public files.
  matcher: ["/((?!auth/|api/|_next/|favicon.ico|.*\\.[a-z0-9]+$).*)"],
};
