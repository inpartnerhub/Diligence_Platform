"use client";

import { App, ConfigProvider } from "antd";
import enUS from "antd/locale/en_US";
import idID from "antd/locale/id_ID";
import type { ReactNode } from "react";

import type { Locale } from "@/i18n/locales";
import { themeConfig } from "@/theme/themeConfig";

export function Providers({
  locale,
  nonce,
  children,
}: Readonly<{ locale: Locale; nonce?: string; children: ReactNode }>) {
  return (
    // Zero runtime: the styles come from antd.generated.css (built from themeConfig), not from CSS-in-JS per request.
    // The CSP nonce covers whatever antd still injects as <style> (style-src has no 'unsafe-inline', issue #79).
    <ConfigProvider
      theme={{ ...themeConfig, zeroRuntime: true }}
      csp={nonce ? { nonce } : undefined}
      locale={locale === "en" ? enUS : idID}
    >
      <App>{children}</App>
    </ConfigProvider>
  );
}
