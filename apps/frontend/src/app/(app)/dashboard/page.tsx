import { getTranslations } from "next-intl/server";

import { ComingSoon } from "@/components/ComingSoon";
import { requireAccess } from "@/lib/auth/session";

export default async function DashboardPage() {
  await requireAccess("dashboard");
  const t = await getTranslations();
  return (
    <ComingSoon
      title={t("comingSoon.title", { feature: t("nav.dashboard") })}
      subtitle={t("comingSoon.subtitle", { sprint: "3–4", requirements: "PRJ-04, PRJ-05" })}
    />
  );
}
