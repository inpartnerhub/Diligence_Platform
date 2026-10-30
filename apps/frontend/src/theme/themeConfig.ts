import type { ThemeConfig } from "antd";

/** Design tokens shared by every page. Change brand values here, not in components. */
export const themeConfig: ThemeConfig = {
  cssVar: { key: "diligence" },
  token: {
    colorPrimary: "#1f4fd6",
    borderRadius: 6,
    fontFamily:
      "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, 'Noto Sans', sans-serif",
  },
};

/** Risk level colors from PRD Appendix A (green / yellow / orange / red). */
export const riskColors = {
  LOW: "#389e0d",
  MEDIUM: "#d4b106",
  HIGH: "#d46b08",
  CRITICAL: "#cf1322",
} as const;
