import { getTranslations } from "next-intl/server";

import { ComingSoon } from "@/components/ComingSoon";
import { requireAccess } from "@/lib/auth/session";

export default async function FindingsPage() {
  await requireAccess("findings");
  const t = await getTranslations();
  return (
    <ComingSoon
      title={t("comingSoon.title", { feature: t("nav.findings") })}
      subtitle={t("comingSoon.subtitle", { sprint: "11–12", requirements: "FND-01…FND-04" })}
    />
  );
}
