import type { ReactNode } from "react";

import { requireAccess } from "@/lib/auth/session";

/** Administration is SUPER_ADMIN only; the backend enforces the same rule on every endpoint. */
export default async function AdminLayout({ children }: Readonly<{ children: ReactNode }>) {
  await requireAccess("admin");
  return children;
}
