"use client";

import { Alert, Button, Card, Input, Space, Typography } from "antd";

/** Public demo gate (branch `demo`): a plain form post, so it works before any script has loaded. */
export function DemoGateForm({ error }: Readonly<{ error: boolean }>) {
  return (
    <div style={{ maxWidth: 440, margin: "0 auto", padding: "96px 16px" }}>
      <Card>
        <form method="post" action="/auth/demo-gate">
          <Space orientation="vertical" size="middle" style={{ width: "100%" }}>
            <div>
              <Typography.Title level={3} style={{ marginBottom: 4 }}>
                Diligence Platform — Demo
              </Typography.Title>
              <Typography.Text type="secondary">
                Masukkan kata sandi demo yang Anda terima untuk membuka halaman pilih peran. Semua data di
                demo ini fiktif dan dikembalikan ke kondisi awal setiap kali demo dinyalakan ulang.
              </Typography.Text>
            </div>
            {error && <Alert type="error" showIcon title="Kata sandi salah. Coba lagi." />}
            <label htmlFor="demo-gate-password">
              <Typography.Text strong>Kata sandi demo</Typography.Text>
            </label>
            <Input.Password
              id="demo-gate-password"
              name="password"
              autoComplete="current-password"
              autoFocus
              required
            />
            <Button type="primary" htmlType="submit" block>
              Masuk ke demo
            </Button>
          </Space>
        </form>
      </Card>
    </div>
  );
}
