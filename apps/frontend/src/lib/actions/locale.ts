"use server";

import { cookies } from "next/headers";

import { LOCALE_COOKIE, toLocale } from "@/i18n/locales";
import { apiFetch } from "@/lib/api/client";

/** Switches the UI language and stores it in the user's profile (best effort). */
export async function setLocale(value: string): Promise<void> {
  const locale = toLocale(value);
  (await cookies()).set(LOCALE_COOKIE, locale, { path: "/", sameSite: "lax", maxAge: 60 * 60 * 24 * 365 });
  try {
    await apiFetch("/api/v1/me", { method: "PATCH", body: { locale } });
  } catch {
    // The cookie alone is enough for this browser.
  }
}
