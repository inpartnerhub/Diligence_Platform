"use client";

import { ClockCircleOutlined } from "@ant-design/icons";
import { Card, Result } from "antd";

export function ComingSoon({ title, subtitle }: Readonly<{ title: string; subtitle: string }>) {
  return (
    <Card>
      <Result icon={<ClockCircleOutlined />} title={title} subTitle={subtitle} />
    </Card>
  );
}
