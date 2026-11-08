const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

const originOf = (url: string | undefined) => {
  try {
    return url ? new URL(url).origin : undefined;
  } catch {
    return undefined;
  }
};

/**
 * CSRF defense in depth for the edge proxy (issue #79). The session cookie is SameSite=Lax and Next.js checks the
 * origin of server actions; this check does not depend on either. A state-changing request is rejected when the
 * browser marks it as coming from another site (Fetch Metadata) or when its Origin is not this app's public origin
 * (`APP_BASE_URL`), which also covers sibling subdomains and opaque `null` origins. Requests without these headers
 * (non-browser clients) carry no session cookie a third party could ride on, so they pass to the usual checks.
 */
export function isCrossSiteWrite(method: string, headers: Headers, appBaseUrl: string | undefined): boolean {
  if (SAFE_METHODS.has(method.toUpperCase())) return false;
  if (headers.get("sec-fetch-site") === "cross-site") return true;
  const origin = headers.get("origin");
  const appOrigin = originOf(appBaseUrl);
  return origin !== null && appOrigin !== undefined && origin !== appOrigin;
}
