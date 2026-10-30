import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

import { findingsApi } from "@/features/findings/api";
import { RiskMatrixView } from "@/features/findings/RiskMatrixView";

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("findings.matrix"))("title") };
}

export default async function RiskMatrixPage() {
  return <RiskMatrixView matrix={await findingsApi.matrix()} />;
}
