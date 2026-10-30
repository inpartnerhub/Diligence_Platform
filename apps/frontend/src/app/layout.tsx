import { AntdRegistry } from "@ant-design/nextjs-registry";
import { ConfigProvider } from "antd";
import type { Metadata } from "next";
import type { ReactNode } from "react";

import { themeConfig } from "@/theme/themeConfig";

export const metadata: Metadata = {
  title: { default: "Platform", template: "%s · Platform" },
  description: "Enterprise platform",
};

export default function RootLayout({ children }: Readonly<{ children: ReactNode }>) {
  return (
    <html lang="en">
      <body style={{ margin: 0 }}>
        <AntdRegistry>
          <ConfigProvider theme={themeConfig}>{children}</ConfigProvider>
        </AntdRegistry>
      </body>
    </html>
  );
}
