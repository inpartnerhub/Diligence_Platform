import { cookies } from "next/headers";
import { getRequestConfig } from "next-intl/server";

import { LOCALE_COOKIE, toLocale } from "./locales";

/** Locale comes from a cookie (no locale prefix in URLs); it is synced with the user's profile at login. */
export default getRequestConfig(async () => {
  const locale = toLocale((await cookies()).get(LOCALE_COOKIE)?.value);
  return {
    locale,
    messages: (await import(`../../messages/${locale}.json`)).default,
    timeZone: "Asia/Jakarta",
  };
});
