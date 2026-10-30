import * as Sentry from "@sentry/nextjs";

export async function register() {
  if (process.env.NEXT_PUBLIC_SENTRY_DSN) {
    Sentry.init({
      dsn: process.env.NEXT_PUBLIC_SENTRY_DSN,
      environment: process.env.NEXT_PUBLIC_APP_ENVIRONMENT,
      tracesSampleRate: 0, // tracing is handled by OpenTelemetry
      dataCollection: { userInfo: false, cookies: false },
    });
  }
}

export const onRequestError = Sentry.captureRequestError;
