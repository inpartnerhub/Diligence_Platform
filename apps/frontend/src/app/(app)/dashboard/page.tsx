import { getTranslations } from "next-intl/server";

import { ComingSoon } from "@/components/ComingSoon";
import { requireAccess } from "@/lib/auth/session";

/** Portfolio dashboard (PRD PRJ-05, phase 2). Per-project dashboards live on each project page (PRJ-04). */
export default async function DashboardPage() {
  await requireAccess("dashboard");
  const t = await getTranslations("comingSoon");
  return <ComingSoon title={t("portfolioTitle")} subtitle={t("portfolioSubtitle")} />;
}
