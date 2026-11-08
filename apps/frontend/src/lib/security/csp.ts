interface PolicyOptions {
  /** Fresh per request; Next.js adds it to its own scripts when it finds it in the request's CSP header. */
  nonce: string;
  /** React Refresh and source maps evaluate code in development. */
  development: boolean;
  /** Keycloak: the logout form is redirected there (Chrome applies form-action to redirects). */
  issuer?: string;
  /** Browser errors are sent straight to Sentry. */
  sentryDsn?: string;
}

const originOf = (url: string | undefined) => {
  try {
    return url ? new URL(url).origin : undefined;
  } catch {
    return undefined;
  }
};

/**
 * Content-Security-Policy for pages. Scripts run only with this request's nonce (and what they load, via
 * strict-dynamic). Style elements need the nonce too: Ant Design runs in zero-runtime mode from a build-time
 * stylesheet, so nothing injects CSS-in-JS. Style attributes (React `style` props, popup positioning) stay allowed
 * through style-src-attr: an attribute holds declarations only, no selectors, so it cannot read the page the way an
 * injected <style> element can (issue #79). Development keeps 'unsafe-inline' for elements, since dev-mode CSS is
 * injected as <style> without a nonce.
 * Preview pages and downloads are same-origin route handlers, so images and requests need nothing else.
 */
export function contentSecurityPolicy({ nonce, development, issuer, sentryDsn }: PolicyOptions): string {
  const extra = (value: string | undefined) => (value ? ` ${value}` : "");
  return [
    "default-src 'self'",
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'${development ? " 'unsafe-eval'" : ""}`,
    `style-src 'self' ${development ? "'unsafe-inline'" : `'nonce-${nonce}'`}`,
    "style-src-attr 'unsafe-inline'",
    "img-src 'self' data: blob:",
    "font-src 'self' data:",
    `connect-src 'self'${extra(originOf(sentryDsn))}`,
    `form-action 'self'${extra(originOf(issuer))}`,
    "frame-ancestors 'none'",
    "base-uri 'none'",
    "object-src 'none'",
  ].join("; ");
}

/** Route handlers and auth redirects never render HTML: nothing may run or embed them. */
export const NON_DOCUMENT_POLICY = "default-src 'none'; frame-ancestors 'none'; sandbox";
