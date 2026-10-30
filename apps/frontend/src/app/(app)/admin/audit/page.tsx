import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

import { adminApi } from "@/features/admin/api";
import { AuditView } from "@/features/admin/AuditView";

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("audit"))("title") };
}

type SearchParams = Promise<Record<string, string | undefined>>;

export default async function AuditPage({ searchParams }: Readonly<{ searchParams: SearchParams }>) {
  const { page, action, objectType } = await searchParams;
  return <AuditView logs={await adminApi.auditLogs({ page, action, objectType, size: 50 })} />;
}
