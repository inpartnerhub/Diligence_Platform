import { getTranslations } from "next-intl/server";

import { ComingSoon } from "@/components/ComingSoon";
import { requireAccess } from "@/lib/auth/session";

export default async function DataRoomPage() {
  await requireAccess("dataRoom");
  const t = await getTranslations();
  return (
    <ComingSoon
      title={t("comingSoon.title", { feature: t("nav.dataRoom") })}
      subtitle={t("comingSoon.subtitle", { sprint: "5–8", requirements: "DOC-01…DOC-07" })}
    />
  );
}
