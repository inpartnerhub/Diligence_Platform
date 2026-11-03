import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

import { adminApi } from "@/features/admin/api";
import { WebhooksView } from "@/features/admin/WebhooksView";

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("webhooks"))("title") };
}

export default async function WebhooksPage() {
  const [subscriptions, orgs] = await Promise.all([
    adminApi.webhooks(),
    adminApi.organizations({ size: 100 }),
  ]);
  return <WebhooksView subscriptions={subscriptions} organizations={orgs.items} />;
}
