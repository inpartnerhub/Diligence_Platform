import type { Metadata } from "next";
import { NextIntlClientProvider } from "next-intl";
import { getLocale, getTranslations } from "next-intl/server";
import { headers } from "next/headers";
import type { ReactNode } from "react";

import { Providers } from "@/components/Providers";
import { StyleRegistry } from "@/components/StyleRegistry";

import "./antd.generated.css";
import { toLocale } from "@/i18n/locales";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("app");
  return { title: { default: t("name"), template: `%s · ${t("name")}` }, description: t("tagline") };
}

export default async function RootLayout({ children }: Readonly<{ children: ReactNode }>) {
  const locale = toLocale(await getLocale());
  // Set by src/proxy.ts with the CSP; every <style> Ant Design renders carries it.
  const nonce = (await headers()).get("x-diligence-nonce") ?? undefined;
  return (
    <html lang={locale}>
      <body style={{ margin: 0 }}>
        <StyleRegistry nonce={nonce}>
          <NextIntlClientProvider>
            <Providers locale={locale} nonce={nonce}>
              {children}
            </Providers>
          </NextIntlClientProvider>
        </StyleRegistry>
      </body>
    </html>
  );
}
