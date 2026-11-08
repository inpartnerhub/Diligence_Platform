import { Button, Result } from "antd";
import type { Metadata } from "next";
import Link from "next/link";
import { getTranslations } from "next-intl/server";

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("common"))("notFoundTitle") };
}

/**
 * Replaces Next.js' built-in 404, which renders an inline <style> without the CSP nonce (issue #79).
 */
export default async function NotFound() {
  const t = await getTranslations("common");
  return (
    <Result
      status="404"
      title={t("notFoundTitle")}
      subTitle={t("notFoundBody")}
      extra={
        <Link href="/">
          <Button type="primary">{t("notFoundBack")}</Button>
        </Link>
      }
    />
  );
}
