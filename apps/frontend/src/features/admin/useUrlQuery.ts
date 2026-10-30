"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useCallback } from "react";

/** Keeps table filters and pagination in the URL so views are shareable and server-rendered. */
export function useUrlQuery() {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();

  const update = useCallback(
    (changes: Record<string, string | number | undefined>, resetPage = true) => {
      const next = new URLSearchParams(params.toString());
      for (const [key, value] of Object.entries(changes)) {
        if (value === undefined || value === "") {
          next.delete(key);
        } else {
          next.set(key, String(value));
        }
      }
      if (resetPage && !("page" in changes)) {
        next.delete("page");
      }
      router.push(`${pathname}?${next.toString()}`);
    },
    [params, pathname, router],
  );

  return { params, update };
}
