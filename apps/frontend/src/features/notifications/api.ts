import "server-only";

import { apiFetch } from "@/lib/api/client";

import type { NotificationPreference } from "./types";

export const notificationsApi = {
  preferences: () => apiFetch<NotificationPreference[]>("/api/v1/me/notification-preferences"),
};
