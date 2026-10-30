"use client";

import {
  ArrowLeftOutlined,
  DeleteOutlined,
  FilePdfOutlined,
  FileWordOutlined,
  PlusOutlined,
  SendOutlined,
} from "@ant-design/icons";
import {
  Alert,
  App,
  Button,
  Card,
  Flex,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from "antd";
import type { TableColumnsType } from "antd";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useFormatter, useTranslations } from "next-intl";
import { useState, useTransition } from "react";

import type { Project } from "@/features/projects/types";

import { deleteReport, generateReport, issueReport } from "./actions";
import type { Report, ReportPermissions } from "./types";

/** Report versions of a project (PRD RPT-01): generated from released findings, issued to the client by a partner. */
export function ReportsView({
  project,
  reports,
  permissions,
  releasedFindings,
}: Readonly<{
  project: Project;
  reports: Report[];
  permissions: ReportPermissions;
  releasedFindings: number | null;
}>) {
  const t = useTranslations("reports");
  const format = useFormatter();
  const router = useRouter();
  const { message } = App.useApp();
  const [generating, setGenerating] = useState(false);
  const [pending, startTransition] = useTransition();
  const date = (value: string) =>
    format.dateTime(new Date(value), { dateStyle: "medium", timeStyle: "short" });
  const download = (report: Report, fileFormat: "pdf" | "docx") =>
    `/api/projects/${project.id}/reports/${report.id}/download?format=${fileFormat}`;

  const act = (call: () => Promise<{ ok: boolean; message?: string }>, success: string) =>
    startTransition(async () => {
      const result = await call();
      if (!result.ok) {
        message.error(("message" in result && result.message) || t("error"));
        return;
      }
      message.success(success);
      router.refresh();
    });

  const columns: TableColumnsType<Report> = [
    { title: t("version"), dataIndex: "versionNumber", width: 90, render: (v: number) => `v${v}` },
    { title: t("reportTitle"), dataIndex: "title", ellipsis: true },
    {
      title: t("status"),
      dataIndex: "status",
      width: 120,
      render: (status: Report["status"]) => (
        <Tag color={status === "ISSUED" ? "success" : "default"}>{t(`statusValues.${status}`)}</Tag>
      ),
    },
    { title: t("language"), dataIndex: "language", width: 100, render: (l: string) => t(`languages.${l}`) },
    { title: t("findings"), dataIndex: "findingCount", width: 90, align: "center" },
    {
      title: t("created"),
      dataIndex: "createdAt",
      width: 200,
      render: (value: string, r) => `${date(value)} · ${r.createdByName ?? "—"}`,
    },
    {
      title: t("issued"),
      dataIndex: "issuedAt",
      width: 200,
      render: (value: string | null, r) => (value ? `${date(value)} · ${r.issuedByName ?? "—"}` : "—"),
    },
    {
      title: t("actions"),
      key: "actions",
      width: 220,
      render: (_, r) => (
        <Space wrap>
          <Button size="small" icon={<FilePdfOutlined />} href={download(r, "pdf")}>
            PDF
          </Button>
          {permissions.word && (
            <Button size="small" icon={<FileWordOutlined />} href={download(r, "docx")}>
              Word
            </Button>
          )}
          {permissions.issue && r.status !== "ISSUED" && (
            <Popconfirm
              title={t("issueConfirm")}
              onConfirm={() => act(() => issueReport(project.id, r.id), t("issuedMessage"))}
            >
              <Button size="small" type="primary" icon={<SendOutlined />} loading={pending}>
                {t("issue")}
              </Button>
            </Popconfirm>
          )}
          {permissions.generate && r.status !== "ISSUED" && (
            <Popconfirm
              title={t("deleteConfirm")}
              onConfirm={() => act(() => deleteReport(project.id, r.id), t("deleted"))}
            >
              <Button size="small" danger icon={<DeleteOutlined />} aria-label={t("delete")} />
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <>
      <Flex gap={16} wrap justify="space-between" align="end" style={{ marginBottom: 16 }}>
        <Flex vertical gap={4}>
          <Link href={`/projects/${project.id}`}>
            <ArrowLeftOutlined /> {project.name}
          </Link>
          <Typography.Title level={3} style={{ margin: 0 }}>
            {t("title")} · {project.code}
          </Typography.Title>
        </Flex>
        {permissions.generate && (
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setGenerating(true)}>
            {t("generate")}
          </Button>
        )}
      </Flex>
      {permissions.word ? (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          title={t("teamHint", { count: releasedFindings ?? 0 })}
        />
      ) : (
        <Alert type="info" showIcon style={{ marginBottom: 16 }} title={t("clientHint")} />
      )}
      <Card>
        <Table<Report>
          rowKey="id"
          size="middle"
          columns={columns}
          dataSource={reports}
          scroll={{ x: 1100 }}
          pagination={{ pageSize: 20, hideOnSinglePage: true }}
          locale={{ emptyText: permissions.word ? t("empty") : t("emptyClient") }}
        />
      </Card>
      {generating && (
        <GenerateModal
          projectId={project.id}
          defaultTitle={t("defaultTitle", { project: project.name })}
          onClose={() => setGenerating(false)}
        />
      )}
    </>
  );
}

function GenerateModal({
  projectId,
  defaultTitle,
  onClose,
}: Readonly<{ projectId: string; defaultTitle: string; onClose: () => void }>) {
  const t = useTranslations("reports");
  const { message } = App.useApp();
  const router = useRouter();
  const [form] = Form.useForm<{ title: string; language: "id" | "en" }>();
  const [pending, startTransition] = useTransition();
  return (
    <Modal
      open
      title={t("generate")}
      okText={t("generate")}
      confirmLoading={pending}
      onOk={() => form.submit()}
      onCancel={onClose}
      destroyOnHidden
    >
      <Typography.Paragraph type="secondary">{t("generateHelp")}</Typography.Paragraph>
      <Form
        form={form}
        layout="vertical"
        initialValues={{ title: defaultTitle, language: "id" }}
        onFinish={(values) =>
          startTransition(async () => {
            const result = await generateReport(projectId, values.title, values.language);
            if (!result.ok) {
              message.error(result.message || t("error"));
              return;
            }
            message.success(t("generated", { version: result.data?.versionNumber ?? 0 }));
            router.refresh();
            onClose();
          })
        }
      >
        <Form.Item name="title" label={t("reportTitle")} rules={[{ required: true, whitespace: true }]}>
          <Input maxLength={200} />
        </Form.Item>
        <Form.Item name="language" label={t("language")}>
          <Select
            options={[
              { value: "id", label: t("languages.id") },
              { value: "en", label: t("languages.en") },
            ]}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
}
