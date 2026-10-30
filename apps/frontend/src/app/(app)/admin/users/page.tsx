import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

import { adminApi } from "@/features/admin/api";
import { UsersView } from "@/features/admin/UsersView";

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("users"))("title") };
}

type SearchParams = Promise<Record<string, string | undefined>>;

export default async function UsersPage({ searchParams }: Readonly<{ searchParams: SearchParams }>) {
  const { page, q, role, status } = await searchParams;
  const [users, organizations] = await Promise.all([
    adminApi.users({ page, q, role, status, size: 20 }),
    adminApi.organizations(),
  ]);
  return <UsersView users={users} organizations={organizations.items} />;
}
