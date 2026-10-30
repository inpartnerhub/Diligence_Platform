import type { ThemeConfig } from "antd";

/** Design tokens shared by every page. Change brand values here, not in components. */
export const themeConfig: ThemeConfig = {
  cssVar: { key: "platform" },
  token: {
    colorPrimary: "#1f4fd6",
    borderRadius: 6,
    fontFamily:
      "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, 'Noto Sans', sans-serif",
  },
};
