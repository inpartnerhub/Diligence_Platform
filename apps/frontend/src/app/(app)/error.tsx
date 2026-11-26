"use client";

import * as Sentry from "@sentry/nextjs";
import { Button, Result } from "antd";
import { useTranslations } from "next-intl";
import { useEffect } from "react";

/**
 * Error boundary for the authenticated app. Replaces Next.js' built-in error screen, which renders an inline
 * <style> without the CSP nonce (issue #79), with a translated Ant Design page, and reports the error to Sentry.
 */
export default function AppError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  const t = useTranslations("common");
  useEffect(() => {
    Sentry.captureException(error);
  }, [error]);
  return (
    <Result
      status="error"
      title={t("errorTitle")}
      subTitle={t("error")}
      extra={
        <Button type="primary" onClick={reset}>
          {t("errorRetry")}
        </Button>
      }
    />
  );
}
