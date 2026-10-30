import type { ReactNode } from "react";

import { AppShell } from "@/components/AppShell";
import { accessibleNav } from "@/lib/auth/permissions";
import { requireSession } from "@/lib/auth/session";

export default async function AuthenticatedLayout({ children }: Readonly<{ children: ReactNode }>) {
  const session = await requireSession();
  return (
    <AppShell user={session.user} nav={accessibleNav(session.user.roles)}>
      {children}
    </AppShell>
  );
}
