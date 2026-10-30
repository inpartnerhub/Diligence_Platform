"use client";

// Ant Design compound components (Typography.Title, …) only resolve on the client.
import { Card, Flex, Typography } from "antd";

export default function HomePage() {
  return (
    <Flex justify="center" align="center" style={{ minHeight: "100vh", padding: 24 }}>
      <Card style={{ maxWidth: 560 }}>
        <Typography.Title level={2}>Platform</Typography.Title>
        <Typography.Paragraph type="secondary">
          Project skeleton is running. Feature modules are added under <code>src/features</code>.
        </Typography.Paragraph>
      </Card>
    </Flex>
  );
}
