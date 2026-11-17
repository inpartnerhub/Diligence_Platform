"use client";

import { Alert, Card, Col, Row, Space, Spin, Tag, Typography } from "antd";
import { useState } from "react";

import type { DemoRole } from "@/lib/demo/roles";

const SIDE_COLOR: Record<DemoRole["side"], string> = {
  "Tim konsultan": "blue",
  Klien: "green",
  "Pihak target": "orange",
  Audit: "purple",
  Administrasi: "default",
};

/** Demo role picker (branch `demo`): a card per role; the link signs in on the server and opens the dashboard. */
export function DemoRolePicker({ roles, error }: Readonly<{ roles: readonly DemoRole[]; error: boolean }>) {
  const [signingIn, setSigningIn] = useState<string | null>(null);
  return (
    <div style={{ maxWidth: 1080, margin: "0 auto", padding: "48px 16px" }}>
      <Space orientation="vertical" size="large" style={{ width: "100%" }}>
        <div>
          <Typography.Title level={2} style={{ marginBottom: 4 }}>
            Diligence Platform — Demo
          </Typography.Title>
          <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
            Pilih peran untuk masuk langsung ke aplikasi, tanpa form login. Semua peran adalah anggota proyek
            demo &ldquo;DEMO-001 Akuisisi PT Target Sejahtera&rdquo;. Untuk berganti peran, klik &ldquo;Mode
            demo · Ganti peran&rdquo; di kanan atas aplikasi.
          </Typography.Paragraph>
        </div>
        {error && (
          <Alert
            type="error"
            showIcon
            title="Gagal masuk sebagai peran ini"
            description="Pastikan user demo sudah dibuat (make demo-setup) dan Keycloak berjalan, lalu coba lagi."
          />
        )}
        <Spin
          spinning={signingIn !== null}
          description={signingIn ? `Masuk sebagai ${signingIn}…` : undefined}
        >
          <Row gutter={[16, 16]}>
            {roles.map((role) => (
              <Col key={role.key} xs={24} sm={12} lg={8}>
                {/* A plain link: the sign-in is a route handler that redirects to the dashboard. */}
                <a
                  href={`/auth/demo/${role.key}`}
                  onClick={() => setSigningIn(role.title)}
                  style={{ display: "block", height: "100%" }}
                >
                  <Card hoverable style={{ height: "100%" }}>
                    <Space orientation="vertical" size={4}>
                      <Tag color={SIDE_COLOR[role.side]}>{role.side}</Tag>
                      <Typography.Title level={4} style={{ margin: 0 }}>
                        {role.title}
                      </Typography.Title>
                      <Typography.Text type="secondary">{role.description}</Typography.Text>
                      <Typography.Text code>{role.username}</Typography.Text>
                    </Space>
                  </Card>
                </a>
              </Col>
            ))}
          </Row>
        </Spin>
        <Typography.Text type="secondary">
          Mode demo: semua data fiktif dan dikembalikan ke kondisi awal setiap kali demo dinyalakan ulang.
          Mode ini tidak pernah aktif di environment yang sebenarnya.
        </Typography.Text>
      </Space>
    </div>
  );
}
