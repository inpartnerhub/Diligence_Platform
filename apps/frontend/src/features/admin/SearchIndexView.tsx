"use client";

import { ReloadOutlined } from "@ant-design/icons";
import { Alert, App, Button, Card, Descriptions, Popconfirm, Progress, Tag, Typography } from "antd";
import { useRouter } from "next/navigation";
import { useFormatter, useTranslations } from "next-intl";
import { useEffect, useTransition } from "react";

import { PageHeader } from "@/components/PageHeader";
import { useStepUp } from "@/lib/auth/useStepUp";

import { startSearchReindex } from "./actions";
import { reindexPercent, type SearchReindexRun } from "./types";

const STATUS_COLOR: Record<SearchReindexRun["status"], string> = {
  RUNNING: "processing",
  COMPLETED: "success",
  FAILED: "error",
};

/** Rebuilds the search index from the database (#80), e.g. after a restore. Polls while a run is going on. */
export function SearchIndexView({ run }: Readonly<{ run: SearchReindexRun | null }>) {
  const t = useTranslations("searchIndex");
  const format = useFormatter();
  const router = useRouter();
  const { message } = App.useApp();
  const stepUp = useStepUp(); // a re-index needs a recent sign-in (#65)
  const [pending, startTransition] = useTransition();
  const running = run?.status === "RUNNING";

  useEffect(() => {
    if (!running) return;
    const timer = setInterval(() => router.refresh(), 5000);
    return () => clearInterval(timer);
  }, [running, router]);

  const date = (value: string | null) =>
    value ? format.dateTime(new Date(value), { dateStyle: "medium", timeStyle: "medium" }) : "—";
  const count = (value: number) => format.number(value);

  const start = () =>
    startTransition(async () => {
      const res = await startSearchReindex();
      if (res.ok) {
        message.success(t("started"));
        router.refresh();
      } else if (!stepUp(res)) {
        message.error(res.message || t("error"));
      }
    });

  return (
    <>
      <PageHeader
        title={t("title")}
        extra={
          <Popconfirm
            title={t("confirmTitle")}
            description={t("confirmBody")}
            okText={t("start")}
            onConfirm={start}
            disabled={running}
          >
            <Button type="primary" icon={<ReloadOutlined />} loading={pending} disabled={running}>
              {t("start")}
            </Button>
          </Popconfirm>
        }
      />
      <Typography.Paragraph type="secondary">{t("subtitle")}</Typography.Paragraph>
      <Card title={t("latestRun")}>
        {run ? (
          <>
            <Progress
              percent={reindexPercent(run)}
              status={run.status === "FAILED" ? "exception" : running ? "active" : "normal"}
              style={{ marginBottom: 16 }}
            />
            <Descriptions column={{ xs: 1, md: 2 }} size="small" bordered>
              <Descriptions.Item label={t("status")}>
                <Tag color={STATUS_COLOR[run.status]}>{t(`statuses.${run.status}`)}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t("documents")}>{count(run.documentsTotal)}</Descriptions.Item>
              <Descriptions.Item label={t("startedAt")}>{date(run.startedAt)}</Descriptions.Item>
              <Descriptions.Item label={t("finishedAt")}>{date(run.finishedAt)}</Descriptions.Item>
              <Descriptions.Item label={t("indexed")}>{count(run.indexed)}</Descriptions.Item>
              <Descriptions.Item label={t("failed")}>{count(run.failed)}</Descriptions.Item>
              <Descriptions.Item label={t("removed")}>
                {run.removed === null ? t("removedSkipped") : count(run.removed)}
              </Descriptions.Item>
              <Descriptions.Item label={t("heartbeatAt")}>{date(run.heartbeatAt)}</Descriptions.Item>
            </Descriptions>
            {run.error && (
              <Alert
                style={{ marginTop: 16 }}
                type={run.status === "FAILED" ? "error" : "warning"}
                showIcon
                message={run.error}
              />
            )}
          </>
        ) : (
          <Typography.Text type="secondary">{t("never")}</Typography.Text>
        )}
      </Card>
    </>
  );
}
