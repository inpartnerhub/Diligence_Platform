"use client";

import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  DeleteOutlined,
  DiffOutlined,
  FilePdfOutlined,
  FileExcelOutlined,
  FilePptOutlined,
  FileWordOutlined,
  HistoryOutlined,
  PlusOutlined,
  RollbackOutlined,
  SendOutlined,
} from "@ant-design/icons";
import {
  Alert,
  App,
  Button,
  Card,
  Drawer,
  Empty,
  Flex,
  Form,
  Input,
  Modal,
  Popconfirm,
  Radio,
  Select,
  Space,
  Table,
  Tag,
  Timeline,
  Tooltip,
  Typography,
} from "antd";
import type { TableColumnsType } from "antd";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useFormatter, useTranslations } from "next-intl";
import { useState, useTransition } from "react";

import type { Finding } from "@/features/findings/types";
import type { Project } from "@/features/projects/types";
import { useStepUp } from "@/lib/auth/useStepUp";

import {
  approveReport,
  deleteReport,
  fetchReportHistory,
  generateReport,
  generateReportRevision,
  issueReport,
  releaseReport,
  returnReportDraft,
  submitReport,
} from "./actions";
import type { Report, ReportPermissions, ReportStatusHistoryItem } from "./types";

/**
 * Report versions of a project (PRD RPT-01, RPT-03):
 * Tiered multi-stage approval workflow with revision tracking and audit history:
 * Analyst Draft -> Submit for Review -> Manager Approval -> Partner Client Release.
 */
export function ReportsView({
  project,
  reports,
  permissions,
  releasedFindings,
}: Readonly<{
  project: Project;
  reports: Report[];
  permissions: ReportPermissions;
  releasedFindings: Finding[] | null;
}>) {
  const t = useTranslations("reports");
  const format = useFormatter();
  const router = useRouter();
  const { message } = App.useApp();
  const stepUp = useStepUp();
  const [generating, setGenerating] = useState(false);
  const [revisionTarget, setRevisionTarget] = useState<Report | null>(null);
  const [returnTarget, setReturnTarget] = useState<Report | null>(null);
  const [historyTarget, setHistoryTarget] = useState<Report | null>(null);
  const [historyItems, setHistoryItems] = useState<ReportStatusHistoryItem[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [pending, startTransition] = useTransition();

  const date = (value: string) =>
    format.dateTime(new Date(value), { dateStyle: "medium", timeStyle: "short" });
  const download = (report: Report, fileFormat: "pdf" | "docx" | "pptx" | "xlsx") =>
    `/api/projects/${project.id}/reports/${report.id}/download?format=${fileFormat}`;

  const act = (call: () => Promise<{ ok: boolean; message?: string; stepUp?: true }>, success: string) =>
    startTransition(async () => {
      const result = await call();
      if (!result.ok) {
        if (stepUp(result)) return; // release/issue need a recent sign-in (#65)
        message.error(("message" in result && result.message) || t("error"));
        return;
      }
      message.success(success);
      router.refresh();
    });

  const openHistory = async (report: Report) => {
    setHistoryTarget(report);
    setHistoryLoading(true);
    const res = await fetchReportHistory(project.id, report.id);
    setHistoryLoading(false);
    if (res.ok && res.data) {
      setHistoryItems(res.data);
    } else {
      setHistoryItems([]);
    }
  };

  const statusColor = (status: Report["status"]) => {
    switch (status) {
      case "RELEASED_BY_PARTNER":
      case "ISSUED":
        return "success";
      case "APPROVED_BY_MANAGER":
      case "APPROVED":
        return "warning";
      case "SUBMITTED_FOR_REVIEW":
      case "IN_REVIEW":
        return "processing";
      case "DRAFT":
      default:
        return "default";
    }
  };

  const isReleased = (status: Report["status"]) => status === "RELEASED_BY_PARTNER" || status === "ISSUED";

  const columns: TableColumnsType<Report> = [
    {
      title: t("version"),
      key: "version",
      width: 100,
      render: (_, r) => (
        <Space orientation="vertical" size={2}>
          <Typography.Text strong>{r.versionLabel ?? `v${r.versionNumber}.0`}</Typography.Text>
        </Space>
      ),
    },
    { title: t("reportTitle"), dataIndex: "title", ellipsis: true },
    {
      title: t("status"),
      dataIndex: "status",
      width: 160,
      render: (status: Report["status"]) => (
        <Tag color={statusColor(status)}>{t(`statusValues.${status}`)}</Tag>
      ),
    },
    { title: t("language"), dataIndex: "language", width: 100, render: (l: string) => t(`languages.${l}`) },
    { title: t("findings"), dataIndex: "findingCount", width: 90, align: "center" },
    {
      title: t("created"),
      dataIndex: "createdAt",
      width: 190,
      render: (value: string, r) => `${date(value)} · ${r.createdByName ?? "—"}`,
    },
    {
      title: t("actions"),
      key: "actions",
      width: 320,
      render: (_, r) => {
        const released = isReleased(r.status);
        return (
          <Space wrap size="small">
            <Button size="small" icon={<FilePdfOutlined />} href={download(r, "pdf")}>
              PDF
            </Button>
            {permissions.word && (
              <>
                <Button size="small" icon={<FileWordOutlined />} href={download(r, "docx")}>
                  Word
                </Button>
                <Button size="small" icon={<FilePptOutlined />} href={download(r, "pptx")}>
                  PPTX
                </Button>
                <Button size="small" icon={<FileExcelOutlined />} href={download(r, "xlsx")}>
                  Excel
                </Button>
              </>
            )}

            {/* Stage 1: DRAFT -> Submit for Review */}
            {permissions.submit && r.status === "DRAFT" && (
              <Popconfirm
                title={t("submitReviewConfirm")}
                onConfirm={() => act(() => submitReport(project.id, r.id), t("submittedMessage"))}
              >
                <Button size="small" type="primary" icon={<SendOutlined />} loading={pending}>
                  {t("submitReview")}
                </Button>
              </Popconfirm>
            )}

            {/* Stage 2: SUBMITTED_FOR_REVIEW -> Approve or Return */}
            {permissions.approve && (r.status === "SUBMITTED_FOR_REVIEW" || r.status === "IN_REVIEW") && (
              <Popconfirm
                title={t("approveConfirm")}
                onConfirm={() => act(() => approveReport(project.id, r.id), t("approvedMessage"))}
              >
                <Button size="small" type="primary" icon={<CheckCircleOutlined />} loading={pending}>
                  {t("approve")}
                </Button>
              </Popconfirm>
            )}

            {/* Stage 3: APPROVED_BY_MANAGER -> Release to Client */}
            {permissions.release && (r.status === "APPROVED_BY_MANAGER" || r.status === "APPROVED") && (
              <Popconfirm
                title={t("releaseConfirm")}
                onConfirm={() => act(() => releaseReport(project.id, r.id), t("releasedMessage"))}
              >
                <Button size="small" type="primary" icon={<SendOutlined />} loading={pending}>
                  {t("release")}
                </Button>
              </Popconfirm>
            )}

            {/* Return to Draft button for review or approved reports */}
            {permissions.returnDraft && !released && r.status !== "DRAFT" && (
              <Button
                size="small"
                icon={<RollbackOutlined />}
                onClick={() => setReturnTarget(r)}
                loading={pending}
              >
                {t("returnDraft")}
              </Button>
            )}

            {/* Partner direct issue button for legacy backward compatibility */}
            {permissions.issue && !released && r.status === "DRAFT" && (
              <Popconfirm
                title={t("issueConfirm")}
                onConfirm={() => act(() => issueReport(project.id, r.id), t("issuedMessage"))}
              >
                <Tooltip title={t("issue")}>
                  <Button
                    size="small"
                    type="primary"
                    icon={<SendOutlined />}
                    loading={pending}
                    aria-label={t("issue")}
                  >
                    {t("issue")}
                  </Button>
                </Tooltip>
              </Popconfirm>
            )}

            {/* New revision button for released reports */}
            {permissions.generate && released && (
              <Tooltip title={t("newRevision")}>
                <Button
                  size="small"
                  type="dashed"
                  icon={<DiffOutlined />}
                  onClick={() => setRevisionTarget(r)}
                >
                  {t("newRevision")}
                </Button>
              </Tooltip>
            )}

            {/* History drawer button */}
            <Tooltip title={t("history")}>
              <Button size="small" icon={<HistoryOutlined />} onClick={() => openHistory(r)} />
            </Tooltip>

            {/* Delete button (DRAFT only) */}
            {permissions.generate && r.status === "DRAFT" && (
              <Popconfirm
                title={t("deleteConfirm")}
                onConfirm={() => act(() => deleteReport(project.id, r.id), t("deleted"))}
              >
                <Button size="small" danger icon={<DeleteOutlined />} aria-label={t("delete")} />
              </Popconfirm>
            )}
          </Space>
        );
      },
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
          title={t("teamHint", { count: releasedFindings?.length ?? 0 })}
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
          scroll={{ x: 1200 }}
          pagination={{ pageSize: 20, hideOnSinglePage: true }}
          locale={{ emptyText: permissions.word ? t("empty") : t("emptyClient") }}
        />
      </Card>
      {generating && (
        <GenerateModal
          projectId={project.id}
          findings={releasedFindings ?? []}
          defaultTitle={t("defaultTitle", { project: project.name })}
          onClose={() => setGenerating(false)}
        />
      )}
      {revisionTarget && (
        <RevisionModal
          projectId={project.id}
          findings={releasedFindings ?? []}
          report={revisionTarget}
          onClose={() => setRevisionTarget(null)}
        />
      )}
      {returnTarget && (
        <ReturnModal projectId={project.id} report={returnTarget} onClose={() => setReturnTarget(null)} />
      )}
      <Drawer
        open={Boolean(historyTarget)}
        title={`${t("historyTitle")} — ${historyTarget?.versionLabel ?? historyTarget?.title ?? ""}`}
        onClose={() => setHistoryTarget(null)}
        width={480}
      >
        {historyLoading ? (
          <Typography.Paragraph type="secondary">{t("title")}...</Typography.Paragraph>
        ) : historyItems.length === 0 ? (
          <Empty description={t("historyEmpty")} />
        ) : (
          <Timeline
            items={historyItems.map((item) => ({
              color: statusColor(item.toStatus as Report["status"]),
              children: (
                <Space orientation="vertical" size={2} style={{ display: "flex" }}>
                  <Typography.Text strong>
                    {t(`statusValues.${item.toStatus}` as Parameters<typeof t>[0])}
                  </Typography.Text>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {date(item.changedAt)} · {item.changedByName ?? "—"}
                  </Typography.Text>
                  {item.note && (
                    <Typography.Paragraph type="secondary" style={{ margin: "4px 0 0", fontStyle: "italic" }}>
                      &ldquo;{item.note}&rdquo;
                    </Typography.Paragraph>
                  )}
                </Space>
              ),
            }))}
          />
        )}
      </Drawer>
    </>
  );
}

/** Picker for the released findings a report version includes (PRD RPT-01 "pilih temuan yang dirilis"). */
function FindingPicker({ findings }: Readonly<{ findings: Finding[] }>) {
  const t = useTranslations("reports");
  return (
    <Form.Item
      name="findingIds"
      label={t("findingsToInclude")}
      extra={t("findingsToIncludeHelp")}
      rules={[{ required: true, type: "array", min: 1, message: t("findingsToIncludeRequired") }]}
    >
      <Select
        mode="multiple"
        allowClear
        maxTagCount="responsive"
        optionFilterProp="label"
        options={findings.map((f) => ({ value: f.id, label: `${f.number} · ${f.title}` }))}
      />
    </Form.Item>
  );
}

function GenerateModal({
  projectId,
  findings,
  defaultTitle,
  onClose,
}: Readonly<{ projectId: string; findings: Finding[]; defaultTitle: string; onClose: () => void }>) {
  const t = useTranslations("reports");
  const { message } = App.useApp();
  const router = useRouter();
  const [form] = Form.useForm<{ title: string; language: "id" | "en"; findingIds: string[] }>();
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
        initialValues={{ title: defaultTitle, language: "id", findingIds: findings.map((f) => f.id) }}
        onFinish={(values) =>
          startTransition(async () => {
            const result = await generateReport(projectId, values.title, values.language, values.findingIds);
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
        <FindingPicker findings={findings} />
      </Form>
    </Modal>
  );
}

function RevisionModal({
  projectId,
  findings,
  report,
  onClose,
}: Readonly<{ projectId: string; findings: Finding[]; report: Report; onClose: () => void }>) {
  const t = useTranslations("reports");
  const { message } = App.useApp();
  const router = useRouter();
  const [form] = Form.useForm<{
    major: boolean;
    title: string;
    language: "id" | "en";
    findingIds: string[];
  }>();
  const [pending, startTransition] = useTransition();

  return (
    <Modal
      open
      title={t("revisionModalTitle")}
      okText={t("generate")}
      confirmLoading={pending}
      onOk={() => form.submit()}
      onCancel={onClose}
      destroyOnHidden
    >
      <Typography.Paragraph type="secondary">{t("revisionHelp")}</Typography.Paragraph>
      <Form
        form={form}
        layout="vertical"
        initialValues={{
          major: false,
          title: report.title,
          language: report.language,
          findingIds: findings.map((f) => f.id),
        }}
        onFinish={(values) =>
          startTransition(async () => {
            const result = await generateReportRevision(
              projectId,
              report.id,
              values.major,
              values.title,
              values.language,
              values.findingIds,
            );
            if (!result.ok) {
              message.error(result.message || t("error"));
              return;
            }
            message.success(t("revisionCreated", { version: result.data?.versionLabel ?? "" }));
            router.refresh();
            onClose();
          })
        }
      >
        <Form.Item name="major" label={t("revisionType")} rules={[{ required: true }]}>
          <Radio.Group>
            <Space orientation="vertical">
              <Radio value={false}>{t("minorRevision")}</Radio>
              <Radio value={true}>{t("majorRevision")}</Radio>
            </Space>
          </Radio.Group>
        </Form.Item>
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
        <FindingPicker findings={findings} />
      </Form>
    </Modal>
  );
}

function ReturnModal({
  projectId,
  report,
  onClose,
}: Readonly<{ projectId: string; report: Report; onClose: () => void }>) {
  const t = useTranslations("reports");
  const { message } = App.useApp();
  const router = useRouter();
  const [form] = Form.useForm<{ reason: string }>();
  const [pending, startTransition] = useTransition();

  return (
    <Modal
      open
      title={t("returnDraft")}
      okText={t("returnDraft")}
      okButtonProps={{ danger: true }}
      confirmLoading={pending}
      onOk={() => form.submit()}
      onCancel={onClose}
      destroyOnHidden
    >
      <Typography.Paragraph type="secondary">
        {report.versionLabel ?? `v${report.versionNumber}`} — {report.title}
      </Typography.Paragraph>
      <Form
        form={form}
        layout="vertical"
        onFinish={(values) =>
          startTransition(async () => {
            const result = await returnReportDraft(projectId, report.id, values.reason);
            if (!result.ok) {
              message.error(result.message || t("error"));
              return;
            }
            message.success(t("returnedMessage"));
            router.refresh();
            onClose();
          })
        }
      >
        <Form.Item
          name="reason"
          label={t("returnReason")}
          rules={[{ required: true, whitespace: true, message: t("returnReason") }]}
        >
          <Input.TextArea rows={3} maxLength={1000} showCount />
        </Form.Item>
      </Form>
    </Modal>
  );
}
