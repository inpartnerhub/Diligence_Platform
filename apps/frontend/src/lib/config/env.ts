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
  get apiBaseUrl(): string {
    return required("API_BASE_URL", process.env.API_BASE_URL);
  },
};

export const publicEnv = {
  oidcIssuer: process.env.NEXT_PUBLIC_OIDC_ISSUER ?? "",
  oidcClientId: process.env.NEXT_PUBLIC_OIDC_CLIENT_ID ?? "",
  environment: process.env.NEXT_PUBLIC_APP_ENVIRONMENT ?? "local",
  sentryDsn: process.env.NEXT_PUBLIC_SENTRY_DSN ?? "",
} as const;
