import { withSentryConfig } from "@sentry/nextjs/config";
import type { NextConfig } from "next";
import createNextIntlPlugin from "next-intl/plugin";

import { NON_DOCUMENT_POLICY } from "./src/lib/security/csp";

const withNextIntl = createNextIntlPlugin("./src/i18n/request.ts");

const securityHeaders = [
  { key: "Strict-Transport-Security", value: "max-age=63072000; includeSubDomains; preload" },
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "X-Frame-Options", value: "DENY" },
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=()" },
];

const nextConfig: NextConfig = {
  output: "standalone", // minimal self-contained server for the container image
  poweredByHeader: false,
  reactStrictMode: true,
  transpilePackages: ["antd", "@ant-design/icons"],
  serverExternalPackages: ["redis"],
  async headers() {
    // Pages get their nonce-based Content-Security-Policy from src/proxy.ts.
    const nonDocument = [{ key: "Content-Security-Policy", value: NON_DOCUMENT_POLICY }];
    return [
      { source: "/:path*", headers: securityHeaders },
      { source: "/api/:path*", headers: nonDocument },
      { source: "/auth/:path*", headers: nonDocument },
    ];
  },
};

export default withSentryConfig(withNextIntl(nextConfig), {
  silent: true,
  // Source maps are uploaded only when CI provides SENTRY_AUTH_TOKEN.
  sourcemaps: { disable: !process.env.SENTRY_AUTH_TOKEN },
  telemetry: false,
});
