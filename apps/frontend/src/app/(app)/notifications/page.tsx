import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

import { loadInbox } from "@/features/notifications/actions";
import { notificationsApi } from "@/features/notifications/api";
import { NotificationsView } from "@/features/notifications/NotificationsView";
import { requireSession } from "@/lib/auth/session";

type SearchParams = Promise<Record<string, string | undefined>>;

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("notifications"))("title") };
}

/** Every signed-in user has an inbox and notification preferences (PRD ADM-05). */
export default async function NotificationsPage({ searchParams }: Readonly<{ searchParams: SearchParams }>) {
  await requireSession();
  const { page, unread } = await searchParams;
  const unreadOnly = unread === "true";
  const [inbox, preferences] = await Promise.all([
    loadInbox(Math.max(0, Number(page) || 0), unreadOnly),
    notificationsApi.preferences(),
  ]);
  return <NotificationsView inbox={inbox} unreadOnly={unreadOnly} preferences={preferences} />;
}
