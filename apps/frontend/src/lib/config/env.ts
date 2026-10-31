/**
 * Single access point for configuration. Server values are read lazily so `next build` does not require them;
 * public values are inlined at build time by Next.js.
 */
function required(name: string, value: string | undefined): string {
  if (!value) {
    throw new Error(`Missing required environment variable ${name}`);
  }
  return value;
}

export const serverEnv = {
  /** Public origin of this app, e.g. https://app.example.com — used for OIDC redirect URIs. */
  get appBaseUrl(): string {
    return required("APP_BASE_URL", process.env.APP_BASE_URL);
  },
  get apiBaseUrl(): string {
    return required("API_BASE_URL", process.env.API_BASE_URL);
  },
  /** Issuer as the browser sees it (and as stamped into tokens). */
  get oidcIssuer(): string {
    return required("OIDC_ISSUER", process.env.OIDC_ISSUER);
  },
  /** Issuer base reachable from this server for back-channel calls; defaults to the public issuer. */
  get oidcInternalIssuer(): string {
    return process.env.OIDC_INTERNAL_ISSUER || this.oidcIssuer;
  },
  get oidcClientId(): string {
    return required("OIDC_CLIENT_ID", process.env.OIDC_CLIENT_ID);
  },
  get sessionSecret(): string {
    const secret = required("SESSION_SECRET", process.env.SESSION_SECRET);
    if (secret.length < 32) {
      throw new Error("SESSION_SECRET must be at least 32 characters");
    }
    return secret;
  },
  get redisUrl(): string {
    return required("REDIS_URL", process.env.REDIS_URL);
  },
  /** Cookies get the Secure flag whenever the app is served over HTTPS. */
  get secureCookies(): boolean {
    return this.appBaseUrl.startsWith("https://");
  },
  /** Proxies in front of this server that append to X-Forwarded-For (the ingress); 0 when browsers connect directly. */
  get trustedProxyHops(): number {
    const hops = Number(process.env.TRUSTED_PROXY_HOPS ?? "0");
    if (!Number.isInteger(hops) || hops < 0) {
      throw new Error("TRUSTED_PROXY_HOPS must be a non-negative integer");
    }
    return hops;
  },
  /** Plain-HTTP identity provider, local stack only. Never set in deployed environments. */
  get oidcAllowHttp(): boolean {
    return process.env.OIDC_ALLOW_HTTP === "true";
  },
};

export const publicEnv = {
  environment: process.env.NEXT_PUBLIC_APP_ENVIRONMENT ?? "local",
  sentryDsn: process.env.NEXT_PUBLIC_SENTRY_DSN ?? "",
} as const;
