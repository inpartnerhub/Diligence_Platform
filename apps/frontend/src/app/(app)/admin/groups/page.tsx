import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

import { adminApi } from "@/features/admin/api";
import { GroupsView } from "@/features/admin/GroupsView";

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("groups"))("title") };
}

export default async function GroupsPage() {
  const [groups, users] = await Promise.all([adminApi.groups(), adminApi.users({ size: 500 })]);
  return <GroupsView groups={groups} users={users.items} />;
}
