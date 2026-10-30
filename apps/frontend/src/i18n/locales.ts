/** UI languages (PRD Bab 7: Indonesian and English). Indonesian is the default. */
export const locales = ["id", "en"] as const;
export type Locale = (typeof locales)[number];
export const defaultLocale: Locale = "id";
export const LOCALE_COOKIE = "NEXT_LOCALE";

export function toLocale(value: string | undefined | null): Locale {
  return locales.includes(value as Locale) ? (value as Locale) : defaultLocale;
}
