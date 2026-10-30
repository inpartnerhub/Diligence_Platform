"use client";

import { Flex, Typography } from "antd";
import type { ReactNode } from "react";

export function PageHeader({ title, extra }: Readonly<{ title: string; extra?: ReactNode }>) {
  return (
    <Flex justify="space-between" align="center" wrap gap={12} style={{ marginBottom: 16 }}>
      <Typography.Title level={3} style={{ margin: 0 }}>
        {title}
      </Typography.Title>
      {extra}
    </Flex>
  );
}
