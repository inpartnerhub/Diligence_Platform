import { getTranslations } from "next-intl/server";

import { ComingSoon } from "@/components/ComingSoon";
import { requireAccess } from "@/lib/auth/session";

export default async function ProjectsHomePage() {
  await requireAccess("projects");
  const t = await getTranslations("home");
  return <ComingSoon title={t("title")} subtitle={t("subtitle")} />;
}
