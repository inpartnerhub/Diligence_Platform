import { AntdRegistry } from "@ant-design/nextjs-registry";
import type { Metadata } from "next";
import { NextIntlClientProvider } from "next-intl";
import { getLocale, getTranslations } from "next-intl/server";
import type { ReactNode } from "react";

import { Providers } from "@/components/Providers";

import "./antd.generated.css";
import { toLocale } from "@/i18n/locales";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("app");
  return { title: { default: t("name"), template: `%s · ${t("name")}` }, description: t("tagline") };
}

export default async function RootLayout({ children }: Readonly<{ children: ReactNode }>) {
  const locale = toLocale(await getLocale());
  return (
    <html lang={locale}>
      <body style={{ margin: 0 }}>
        <AntdRegistry>
          <NextIntlClientProvider>
            <Providers locale={locale}>{children}</Providers>
          </NextIntlClientProvider>
        </AntdRegistry>
      </body>
    </html>
  );
}
