import { getTranslations } from "next-intl/server";

import { ComingSoon } from "@/components/ComingSoon";
import { requireAccess } from "@/lib/auth/session";

export default async function ReportsPage() {
  await requireAccess("reports");
  const t = await getTranslations();
  return (
    <ComingSoon
      title={t("comingSoon.title", { feature: t("nav.reports") })}
      subtitle={t("comingSoon.subtitle", { sprint: "13–14", requirements: "RPT-01" })}
    />
  );
}
