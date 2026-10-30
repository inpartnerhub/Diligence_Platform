// Mirrors the backend DTOs in com.inpartnerhub.diligence.notification.internal.

export const NOTIFICATION_TYPES = [
  "REQUEST_RELEASED",
  "REQUEST_SUBMITTED",
  "REQUEST_REVIEWED",
  "REQUEST_COMMENTED",
  "REQUEST_DUE_REMINDER",
  "REQUEST_OVERDUE_DIGEST",
] as const;
export type NotificationType = (typeof NOTIFICATION_TYPES)[number];

export interface InboxEntry {
  id: string;
  type: NotificationType;
  projectId: string | null;
  params: Record<string, string>;
  link: string | null;
  createdAt: string;
  readAt: string | null;
}

export interface NotificationPreference {
  type: NotificationType;
  emailEnabled: boolean;
  inAppEnabled: boolean;
}

/** Only application paths are followed from a notification (never external URLs). */
export function safeLink(link: string | null): string | null {
  return link !== null && link.startsWith("/") && !link.startsWith("//") ? link : null;
}

/** Message key per type; REQUEST_REVIEWED has separate texts for closed and rejected items. */
export function messageKey(entry: Pick<InboxEntry, "type" | "params">): string {
  if (entry.type === "REQUEST_REVIEWED") {
    return entry.params.status === "REJECTED" ? "REQUEST_REVIEWED_REJECTED" : "REQUEST_REVIEWED_CLOSED";
  }
  return entry.type;
}
