import { NextResponse, type NextRequest } from "next/server";

import { contentSecurityPolicy } from "@/lib/security/csp";
import { checkRateLimit, resolveClientIp } from "@/lib/security/rateLimiter";

/**
 * Optimistic check only: no session cookie → start the login flow. The session itself is validated (and tokens
 * refreshed) server-side in the app layout; the backend re-validates every access token. Every response rendered
 * here, including the not-found page for file-like paths such as /robots.txt, gets a Content-Security-Policy with
 * a fresh script nonce.
 *
 * Sensitive paths (auth initiation/callback and server actions) are protected by a sliding-window rate limiter
 * to prevent brute-force attacks and mutation flooding.
 */
// Read by requireSession (src/lib/auth/session.ts); keep the names in sync.
const RETURN_TO_HEADER = "x-diligence-return-to";

const FILE_PATH = /\.[a-z0-9]+$/;

// Rate limiting can be disabled or relaxed in test/CI environments.
// Hard security guardrail: Never allow disabling in production.
const isRateLimitDisabled =
  process.env.RATE_LIMIT_DISABLED === "true" && process.env.NODE_ENV !== "production";

const parseLimit = (value: string | undefined, fallback: number): number => {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
};

const AUTH_LIMIT = {
  windowSeconds: 60,
  maxRequests: parseLimit(process.env.RATE_LIMIT_AUTH_MAX, 30),
};
const ACTION_LIMIT = {
  windowSeconds: 60,
  maxRequests: parseLimit(process.env.RATE_LIMIT_ACTION_MAX, 60),
};

export async function proxy(request: NextRequest) {
  const pathname = request.nextUrl.pathname;
  const isAuth = pathname.startsWith("/auth/");
  const isServerAction =
    request.headers.has("next-action") ||
    (request.method === "POST" && !isAuth && !pathname.startsWith("/api/"));

  if (!isRateLimitDisabled && (isAuth || isServerAction)) {
    const trustedHops = Number(process.env.TRUSTED_PROXY_HOPS ?? "0");
    const ip = resolveClientIp(request.headers, trustedHops);
    const scope = isAuth ? "auth" : "action";
    const options = isAuth ? AUTH_LIMIT : ACTION_LIMIT;
    const result = await checkRateLimit(`${scope}:${ip}`, options);

    if (!result.allowed) {
      return new NextResponse(
        JSON.stringify({ status: 429, detail: "Too Many Requests", retryAfter: result.retryAfter }),
        {
          status: 429,
          headers: {
            "Content-Type": "application/json",
            "Retry-After": String(result.retryAfter),
            "X-RateLimit-Limit": String(result.limit),
            "X-RateLimit-Remaining": "0",
            "X-RateLimit-Reset": String(result.retryAfter),
          },
        },
      );
    }
  }

  // Auth routes are route handlers with dedicated NON_DOCUMENT_POLICY from next.config.ts; do not inject page CSP.
  if (isAuth) {
    return NextResponse.next();
  }

  const returnTo = pathname + request.nextUrl.search;
  const signedIn = request.cookies.has("diligence_sid");
  if (!signedIn && !FILE_PATH.test(pathname)) {
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
  // Everything except API route handlers (they check the session themselves and must stream large
  // uploads, which the proxy would buffer) and Next.js build assets.
  matcher: ["/((?!api/|_next/static/|_next/image|favicon.ico).*)"],
};
