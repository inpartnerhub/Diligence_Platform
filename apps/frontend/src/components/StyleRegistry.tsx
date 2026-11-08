"use client";

import { createCache, extractStyle, StyleProvider } from "@ant-design/cssinjs";
import { useServerInsertedHTML } from "next/navigation";
import { useState, type ReactNode } from "react";

/**
 * Server-side collection of what Ant Design still renders as CSS-in-JS in zero-runtime mode (the theme's CSS
 * variables). Same as @ant-design/nextjs-registry's AntdRegistry, but the <style> carries the request's CSP nonce:
 * style-src has no 'unsafe-inline' (issue #79). Styles injected later in the browser get the nonce from
 * ConfigProvider's `csp` (src/components/Providers.tsx).
 */
export function StyleRegistry({ nonce, children }: Readonly<{ nonce?: string; children: ReactNode }>) {
  const [cache] = useState(() => createCache());
  useServerInsertedHTML(() => {
    const styleText = extractStyle(cache, { plain: true, once: true });
    if (styleText.includes('.data-ant-cssinjs-cache-path{content:"";}')) {
      return null;
    }
    return (
      <style
        id="antd-cssinjs"
        nonce={nonce}
        // Before the styles the browser adds later, as AntdRegistry does.
        data-rc-order="prepend"
        data-rc-priority="-1000"
        dangerouslySetInnerHTML={{ __html: styleText }}
      />
    );
  });
  return <StyleProvider cache={cache}>{children}</StyleProvider>;
}
