"use client";

import * as Sentry from "@sentry/nextjs";
import { useEffect } from "react";

/**
 * Last-resort boundary for when the root layout itself fails: global-error replaces the layout entirely, so there
 * is no i18n provider, no Ant Design stylesheet and no CSP style nonce here. It is deliberately self-contained —
 * plain HTML with inline style attributes only (permitted by `style-src-attr 'unsafe-inline'`), with no <style>
 * element or component CSS that the page CSP would block (issue #79).
 */
export default function GlobalError({ error }: { error: Error & { digest?: string } }) {
  useEffect(() => {
    Sentry.captureException(error);
  }, [error]);
  return (
    <html lang="en">
      <body style={{ margin: 0, fontFamily: "system-ui, sans-serif" }}>
        <main
          style={{
            minHeight: "100vh",
            display: "flex",
            flexDirection: "column",
            alignItems: "center",
            justifyContent: "center",
            gap: "1rem",
            padding: "2rem",
            textAlign: "center",
            color: "#1f1f1f",
          }}
        >
          <h1 style={{ fontSize: "1.5rem", margin: 0 }}>Something went wrong</h1>
          <p style={{ margin: 0, color: "#595959" }}>
            An unexpected error occurred. Please reload the page or try again later.
          </p>
          <button
            type="button"
            onClick={() => window.location.reload()}
            style={{
              padding: "0.5rem 1rem",
              fontSize: "1rem",
              color: "#fff",
              background: "#1677ff",
              border: "none",
              borderRadius: "6px",
              cursor: "pointer",
            }}
          >
            Reload
          </button>
        </main>
      </body>
    </html>
  );
}
