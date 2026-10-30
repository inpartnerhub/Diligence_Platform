"use client";

import { Tag } from "antd";
import { useTranslations } from "next-intl";

import { riskColors } from "@/theme/themeConfig";

import type { RiskLevel } from "./types";

/** Risk level in the Appendix A colours, with the impact × likelihood score. */
export function RiskTag({ level, score }: Readonly<{ level: RiskLevel; score: number | null }>) {
  const t = useTranslations("findings");
  return (
    <Tag color={riskColors[level]}>
      {t(`levels.${level}`)}
      {score !== null ? ` · ${score}` : ""}
    </Tag>
  );
}
