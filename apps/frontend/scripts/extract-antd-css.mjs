// Extracts Ant Design's CSS for our theme into a static stylesheet before `next dev` / `next build`. The app then runs
// antd in zero-runtime mode (src/components/Providers.tsx): no CSS-in-JS is generated per request, and the browser
// caches one stylesheet instead of receiving ~0.5 MB of inline <style> with every page.
import { writeFileSync } from "node:fs";

import { extractStyle } from "@ant-design/static-style-extract";
import { ConfigProvider } from "antd";
import { createElement } from "react";

import { themeConfig } from "../src/theme/themeConfig.ts";

const css = extractStyle((node) => createElement(ConfigProvider, { theme: themeConfig }, node));
const target = new URL("../src/app/antd.generated.css", import.meta.url);
writeFileSync(target, css);
console.log(`Ant Design CSS: ${(css.length / 1024).toFixed(0)} KB → ${target.pathname}`);
