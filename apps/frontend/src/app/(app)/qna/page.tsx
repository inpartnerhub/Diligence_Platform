import { getTranslations } from "next-intl/server";

import { ComingSoon } from "@/components/ComingSoon";
import { requireAccess } from "@/lib/auth/session";

export default async function QnaPage() {
  await requireAccess("qna");
  const t = await getTranslations();
  return (
    <ComingSoon
      title={t("comingSoon.title", { feature: t("nav.qna") })}
      subtitle={t("comingSoon.subtitle", { sprint: "13–14", requirements: "QNA-01…QNA-03" })}
    />
  );
}
