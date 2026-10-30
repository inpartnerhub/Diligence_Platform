"use client";

import { App, Alert, Button, Card, Flex, Form, InputNumber, Typography } from "antd";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import { useTransition } from "react";

import { PageHeader } from "@/components/PageHeader";
import { riskColors } from "@/theme/themeConfig";

import { saveRiskMatrix } from "./actions";
import { levelFor, type RiskMatrix } from "./types";

/** Administration of the impact × likelihood thresholds (PRD FND-03, Appendix A defaults 5 / 10 / 17). */
export function RiskMatrixView({ matrix }: Readonly<{ matrix: RiskMatrix }>) {
  const t = useTranslations("findings.matrix");
  const tl = useTranslations("findings.levels");
  const { message } = App.useApp();
  const router = useRouter();
  const [form] = Form.useForm<RiskMatrix>();
  const [pending, startTransition] = useTransition();
  const values = Form.useWatch([], form) ?? matrix;
  const valid =
    values.mediumFrom > 1 &&
    values.mediumFrom < values.highFrom &&
    values.highFrom < values.criticalFrom &&
    values.criticalFrom <= 25;

  const save = (next: RiskMatrix) =>
    startTransition(async () => {
      const result = await saveRiskMatrix(next);
      if (!result.ok) {
        message.error(result.message || t("error"));
        return;
      }
      message.success(t("saved", { count: result.data?.rerated ?? 0 }));
      router.refresh();
    });

  const scale = [5, 4, 3, 2, 1];
  return (
    <>
      <PageHeader title={t("title")} />
      <Card>
        <Typography.Paragraph type="secondary">{t("help")}</Typography.Paragraph>
        <Form<RiskMatrix> form={form} layout="inline" initialValues={matrix} onFinish={save}>
          <Form.Item name="mediumFrom" label={tl("MEDIUM")}>
            <InputNumber min={2} max={25} />
          </Form.Item>
          <Form.Item name="highFrom" label={tl("HIGH")}>
            <InputNumber min={2} max={25} />
          </Form.Item>
          <Form.Item name="criticalFrom" label={tl("CRITICAL")}>
            <InputNumber min={2} max={25} />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" loading={pending} disabled={!valid}>
              {t("save")}
            </Button>
          </Form.Item>
        </Form>
        {!valid && <Alert type="warning" showIcon style={{ marginTop: 16 }} title={t("invalid")} />}
        <Flex vertical gap={4} style={{ marginTop: 24, maxWidth: 360 }}>
          <Typography.Text type="secondary">{t("preview")}</Typography.Text>
          <div style={{ display: "grid", gridTemplateColumns: "repeat(5, 1fr)", gap: 3 }}>
            {scale.flatMap((impact) =>
              [1, 2, 3, 4, 5].map((likelihood) => {
                const score = impact * likelihood;
                const level = valid ? levelFor(score, values) : levelFor(score, matrix);
                return (
                  <div
                    key={`${impact}:${likelihood}`}
                    style={{
                      background: riskColors[level],
                      color: "#fff",
                      textAlign: "center",
                      lineHeight: "32px",
                      borderRadius: 4,
                    }}
                  >
                    {score}
                  </div>
                );
              }),
            )}
          </div>
          <Typography.Text type="secondary">{t("axes")}</Typography.Text>
        </Flex>
      </Card>
    </>
  );
}
