import * as Sentry from "@sentry/nextjs";

import { publicEnv } from "@/lib/config/env";

if (publicEnv.sentryDsn) {
  Sentry.init({
    dsn: publicEnv.sentryDsn,
    environment: publicEnv.environment,
    tracesSampleRate: 0,
    dataCollection: { userInfo: false, cookies: false },
  });
}

export const onRouterTransitionStart = Sentry.captureRouterTransitionStart;
