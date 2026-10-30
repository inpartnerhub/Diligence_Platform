import { getTranslations } from "next-intl/server";

import { ComingSoon } from "@/components/ComingSoon";
import { requireAccess } from "@/lib/auth/session";

export default async function RequestsPage() {
  await requireAccess("requests");
  const t = await getTranslations();
  return (
    <ComingSoon
      title={t("comingSoon.title", { feature: t("nav.requests") })}
      subtitle={t("comingSoon.subtitle", { sprint: "9–10", requirements: "REQ-01…REQ-05" })}
    />
  );
}
