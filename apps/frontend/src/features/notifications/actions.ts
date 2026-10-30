"use server";

import type { Page } from "@/features/admin/types";
import { ApiError, apiFetch } from "@/lib/api/client";
import { withQuery } from "@/lib/api/query";

import type { InboxEntry, NotificationPreference } from "./types";

/** Unread count for the header bell; 0 when the backend cannot be reached (the bell must never break a page). */
export async function unreadCount(): Promise<number> {
  try {
    return (await apiFetch<{ unread: number }>("/api/v1/me/notifications/unread-count")).unread;
  } catch (error) {
    if (!(error instanceof ApiError)) console.error("Unread count failed", error);
    return 0;
  }
}

export async function loadInbox(page = 0, unreadOnly = false, size = 20): Promise<Page<InboxEntry>> {
  return apiFetch<Page<InboxEntry>>(
    withQuery("/api/v1/me/notifications", { page, size, unreadOnly: String(unreadOnly) }),
  );
}

export async function markRead(id: string): Promise<void> {
  await apiFetch(`/api/v1/me/notifications/${encodeURIComponent(id)}/read`, { method: "POST" });
}

export async function markAllRead(): Promise<void> {
  await apiFetch("/api/v1/me/notifications/read-all", { method: "POST" });
}

export async function savePreferences(
  preferences: NotificationPreference[],
): Promise<{ ok: true; data: NotificationPreference[] } | { ok: false }> {
  try {
    const data = await apiFetch<NotificationPreference[]>("/api/v1/me/notification-preferences", {
      method: "PUT",
      body: { preferences },
    });
    return { ok: true, data };
  } catch (error) {
    console.error("Saving notification preferences failed", error);
    return { ok: false };
  }
}
