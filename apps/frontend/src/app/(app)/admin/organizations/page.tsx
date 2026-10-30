import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

import { adminApi } from "@/features/admin/api";
import { OrganizationsView } from "@/features/admin/OrganizationsView";

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("organizations"))("title") };
}

type SearchParams = Promise<Record<string, string | undefined>>;

export default async function OrganizationsPage({ searchParams }: Readonly<{ searchParams: SearchParams }>) {
  const { page } = await searchParams;
  return <OrganizationsView organizations={await adminApi.organizations({ page, size: 20 })} />;
}
