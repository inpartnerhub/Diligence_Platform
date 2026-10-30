import { NextResponse, type NextRequest } from "next/server";

/**
 * Optimistic check only: no session cookie → start the login flow. The session itself is validated (and tokens
 * refreshed) server-side in the app layout; the backend re-validates every access token.
 */
export function proxy(request: NextRequest) {
  if (request.cookies.has("diligence_sid")) {
    return NextResponse.next();
  }
  const login = new URL("/auth/login", request.url);
  login.searchParams.set("returnTo", request.nextUrl.pathname + request.nextUrl.search);
  return NextResponse.redirect(login);
}

export const config = {
  // Everything except auth routes, health probe, Next.js assets and public files.
  matcher: ["/((?!auth/|api/health|_next/|favicon.ico|.*\\.[a-z0-9]+$).*)"],
};
