"use client";

import { ArrowLeftOutlined, PlusOutlined } from "@ant-design/icons";
import {
  Alert,
  App,
  AutoComplete,
  Button,
  Card,
  Col,
  Flex,
  Form,
  Input,
  InputNumber,
  Modal,
  Row,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  Tooltip,
  Typography,
} from "antd";
import type { TableColumnsType } from "antd";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import { useMemo, useState, useTransition } from "react";

import type { Project, Workstream } from "@/features/projects/types";
import { useUrlQuery } from "@/lib/navigation/useUrlQuery";
import { riskColors } from "@/theme/themeConfig";

import { createFinding, linkFindingDocument, updateFinding } from "./actions";
import { FindingDrawer } from "./FindingDrawer";
import { RiskTag } from "./RiskTag";
import {
  FINDING_STATUSES,
  LEVELS,
  STATUS_COLORS,
  heatMap,
  levelFor,
  sourcedPercent,
  type Finding,
  type FindingCategory,
  type FindingInput,
  type FindingPermissions,
  type FindingStatus,
  type RiskLevel,
  type RiskMatrix,
} from "./types";

export interface FindingListViewProps {
  project: Project;
  findings: Finding[];
  workstreams: Workstream[];
  categories: FindingCategory[];
  matrix: RiskMatrix;
  permissions: FindingPermissions;
  userId: string;
  filters: { status?: string; workstreamId?: string; level?: string; q?: string };
  selectedId: string | null;
  /** Opened from the document viewer: create a finding citing this document and page. */
  source: { documentId: string; page?: string } | null;
}

/** Risk register of one project (PRD FND-01…FND-04); clients see released findings only. */
export function FindingListView({
  project,
  findings,
  workstreams,
  categories,
  matrix,
  permissions,
  userId,
  filters,
  selectedId,
  source,
}: Readonly<FindingListViewProps>) {
  const t = useTranslations("findings");
  const tw = useTranslations("workstreams");
  const { update } = useUrlQuery();
  const [editing, setEditing] = useState<Finding | "new" | null>(source && permissions.edit ? "new" : null);
  const internal = permissions.edit || project.myRoles.includes("OBSERVER");
  const sourced = sourcedPercent(findings);

  const columns: TableColumnsType<Finding> = [
    { title: t("number"), dataIndex: "number", width: 110 },
    { title: t("title"), dataIndex: "title", ellipsis: true },
    {
      title: t("workstream"),
      dataIndex: "workstream",
      width: 130,
      render: (value: Finding["workstream"]) => (value ? tw(value) : null),
    },
    { title: t("category"), dataIndex: "category", width: 150, ellipsis: true },
    {
      title: t("risk"),
      dataIndex: "riskLevel",
      width: 140,
      render: (level: RiskLevel | null, f) =>
        level ? <RiskTag level={level} score={f.riskScore} /> : <Tag>{t("unrated")}</Tag>,
    },
    {
      title: t("status"),
      dataIndex: "status",
      width: 140,
      render: (status: FindingStatus) => (
        <Tag color={STATUS_COLORS[status]}>{t(`statusValues.${status}`)}</Tag>
      ),
    },
    ...(internal
      ? [{ title: t("sources"), dataIndex: "documentCount", width: 90, align: "center" as const }]
      : []),
  ];

  return (
    <>
      <Flex gap={16} wrap justify="space-between" align="end" style={{ marginBottom: 16 }}>
        <Flex vertical gap={4}>
          <Link href={`/projects/${project.id}`}>
            <ArrowLeftOutlined /> {project.name}
          </Link>
          <Typography.Title level={3} style={{ margin: 0 }}>
            {t("pageTitle")} · {project.code}
          </Typography.Title>
        </Flex>
        {permissions.edit && (
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setEditing("new")}>
            {t("newFinding")}
          </Button>
        )}
      </Flex>
      {!internal && <Alert type="info" showIcon style={{ marginBottom: 16 }} title={t("clientHint")} />}

      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={24} lg={10}>
          <Card title={t("heatMap")} size="small">
            <HeatMap findings={findings} matrix={matrix} />
          </Card>
        </Col>
        <Col xs={24} lg={14}>
          <Card size="small" style={{ height: "100%" }}>
            <Flex gap={24} wrap>
              {LEVELS.map((level) => (
                <Statistic
                  key={level}
                  title={<Tag color={riskColors[level]}>{t(`levels.${level}`)}</Tag>}
                  value={findings.filter((f) => f.riskLevel === level && f.status !== "ARCHIVED").length}
                />
              ))}
              {internal && sourced !== null && (
                <Tooltip title={t("sourcedHelp")}>
                  <Statistic title={t("sourced")} value={sourced} suffix="%" />
                </Tooltip>
              )}
            </Flex>
          </Card>
        </Col>
      </Row>

      <Card>
        <Flex gap={8} wrap style={{ marginBottom: 16 }}>
          <Input.Search
            allowClear
            defaultValue={filters.q}
            placeholder={t("searchPlaceholder")}
            style={{ width: 260 }}
            onSearch={(q) => update({ q })}
          />
          {internal && (
            <Select
              allowClear
              placeholder={t("status")}
              value={filters.status}
              style={{ width: 160 }}
              options={FINDING_STATUSES.map((s) => ({ value: s, label: t(`statusValues.${s}`) }))}
              onChange={(status?: string) => update({ status })}
            />
          )}
          <Select
            allowClear
            placeholder={t("workstream")}
            value={filters.workstreamId}
            style={{ width: 180 }}
            options={workstreams.map((w) => ({ value: w.id, label: tw(w.type) }))}
            onChange={(workstreamId?: string) => update({ workstreamId })}
          />
          <Select
            allowClear
            placeholder={t("risk")}
            value={filters.level}
            style={{ width: 150 }}
            options={LEVELS.map((l) => ({ value: l, label: t(`levels.${l}`) }))}
            onChange={(level?: string) => update({ level })}
          />
        </Flex>
        <Table<Finding>
          rowKey="id"
          size="middle"
          columns={columns}
          dataSource={findings}
          scroll={{ x: 900 }}
          pagination={{ pageSize: 50, hideOnSinglePage: true, showSizeChanger: false }}
          locale={{ emptyText: internal ? t("empty") : t("emptyClient") }}
          onRow={(f) => ({ onClick: () => update({ finding: f.id }, false), style: { cursor: "pointer" } })}
        />
      </Card>

      <FindingDrawer
        project={project}
        findingId={selectedId}
        permissions={permissions}
        internal={internal}
        userId={userId}
        onEdit={setEditing}
        onClose={() => update({ finding: undefined }, false)}
      />
      {editing && (
        <FindingFormModal
          projectId={project.id}
          finding={editing === "new" ? null : editing}
          workstreams={workstreams}
          categories={categories}
          matrix={matrix}
          source={editing === "new" ? source : null}
          onClose={() => {
            setEditing(null);
            if (source) update({ document: undefined, page: undefined, new: undefined }, false);
          }}
        />
      )}
    </>
  );
}

/** 5 × 5 impact (rows, 5 at the top) × likelihood (columns) grid coloured by the configured thresholds. */
function HeatMap({ findings, matrix }: Readonly<{ findings: Finding[]; matrix: RiskMatrix }>) {
  const t = useTranslations("findings");
  const cells = useMemo(() => heatMap(findings), [findings]);
  const scale = [1, 2, 3, 4, 5];
  return (
    <Flex gap={8} align="stretch">
      <Typography.Text type="secondary" style={{ writingMode: "vertical-rl", transform: "rotate(180deg)" }}>
        {t("impact")}
      </Typography.Text>
      <div style={{ flex: 1 }}>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(5, 1fr)", gap: 3 }}>
          {[...scale].reverse().flatMap((impact) =>
            scale.map((likelihood) => {
              const count = cells.get(`${impact}:${likelihood}`) ?? 0;
              const level = levelFor(impact * likelihood, matrix);
              return (
                <Tooltip
                  key={`${impact}:${likelihood}`}
                  title={t("cell", { impact, likelihood, score: impact * likelihood, count })}
                >
                  <div
                    style={{
                      background: riskColors[level],
                      opacity: count > 0 ? 1 : 0.35,
                      color: "#fff",
                      fontWeight: 600,
                      textAlign: "center",
                      lineHeight: "30px",
                      borderRadius: 4,
                    }}
                  >
                    {count > 0 ? count : ""}
                  </div>
                </Tooltip>
              );
            }),
          )}
        </div>
        <Typography.Text type="secondary" style={{ display: "block", textAlign: "center", marginTop: 4 }}>
          {t("likelihood")}
        </Typography.Text>
      </div>
    </Flex>
  );
}

interface FormValues {
  workstreamId: string;
  title: string;
  category: string;
  description: string;
  impactDescription?: string;
  monetaryImpact?: number | null;
  currency?: string;
  recommendation?: string;
  impact?: number | null;
  likelihood?: number | null;
  pageReference?: string;
}

function FindingFormModal({
  projectId,
  finding,
  workstreams,
  categories,
  matrix,
  source,
  onClose,
}: Readonly<{
  projectId: string;
  finding: Finding | null;
  workstreams: Workstream[];
  categories: FindingCategory[];
  matrix: RiskMatrix;
  source: { documentId: string; page?: string } | null;
  onClose: () => void;
}>) {
  const t = useTranslations("findings");
  const tw = useTranslations("workstreams");
  const { message } = App.useApp();
  const router = useRouter();
  const [form] = Form.useForm<FormValues>();
  const [pending, startTransition] = useTransition();
  const workstreamId = Form.useWatch("workstreamId", form);
  const impact = Form.useWatch("impact", form);
  const likelihood = Form.useWatch("likelihood", form);
  const preview = impact && likelihood ? levelFor(impact * likelihood, matrix) : null;
  const categoryOptions = categories
    .filter((c) => c.workstreamId === workstreamId)
    .map((c) => ({ value: c.name }));

  const submit = (values: FormValues) =>
    startTransition(async () => {
      const input: FindingInput = {
        workstreamId: values.workstreamId,
        title: values.title,
        category: values.category,
        description: values.description,
        impactDescription: values.impactDescription ?? null,
        monetaryImpact: values.monetaryImpact ?? null,
        currency: values.currency ?? null,
        recommendation: values.recommendation ?? null,
        impact: values.impact ?? null,
        likelihood: values.likelihood ?? null,
      };
      const result = finding
        ? await updateFinding(projectId, finding.id, input)
        : await createFinding(projectId, input);
      if (!result.ok || !result.data) {
        message.error((!result.ok && result.message) || t("error"));
        return;
      }
      if (!finding && source) {
        const linked = await linkFindingDocument(
          projectId,
          result.data.id,
          source.documentId,
          values.pageReference,
        );
        if (!linked.ok) message.warning(linked.message || t("error"));
      }
      message.success(finding ? t("saved") : t("created", { number: result.data.number }));
      router.refresh();
      onClose();
    });

  return (
    <Modal
      open
      width={720}
      title={finding ? t("editTitle", { number: finding.number }) : t("newFinding")}
      okText={finding ? t("save") : t("create")}
      confirmLoading={pending}
      onOk={() => form.submit()}
      onCancel={onClose}
      destroyOnHidden
    >
      {source && <Alert type="info" showIcon style={{ marginBottom: 16 }} title={t("fromDocument")} />}
      <Form<FormValues>
        form={form}
        layout="vertical"
        onFinish={submit}
        initialValues={
          finding
            ? {
                workstreamId: finding.workstreamId,
                title: finding.title,
                category: finding.category,
                description: finding.description,
                impactDescription: finding.impactDescription ?? undefined,
                monetaryImpact: finding.monetaryImpact,
                currency: finding.currency ?? "IDR",
                recommendation: finding.recommendation ?? undefined,
                impact: finding.impact,
                likelihood: finding.likelihood,
              }
            : { workstreamId: workstreams[0]?.id, currency: "IDR", pageReference: source?.page }
        }
      >
        <Flex gap={16} wrap>
          <Form.Item
            name="workstreamId"
            label={t("workstream")}
            rules={[{ required: true }]}
            style={{ minWidth: 200 }}
          >
            <Select options={workstreams.map((w) => ({ value: w.id, label: tw(w.type) }))} />
          </Form.Item>
          <Form.Item
            name="category"
            label={t("category")}
            rules={[{ required: true, whitespace: true }]}
            style={{ flex: 1, minWidth: 220 }}
          >
            <AutoComplete options={categoryOptions} maxLength={200} />
          </Form.Item>
        </Flex>
        <Form.Item name="title" label={t("title")} rules={[{ required: true, whitespace: true }]}>
          <Input maxLength={300} />
        </Form.Item>
        <Form.Item name="description" label={t("description")} rules={[{ required: true, whitespace: true }]}>
          <Input.TextArea rows={4} maxLength={10000} />
        </Form.Item>
        <Form.Item name="impactDescription" label={t("impactDescription")}>
          <Input.TextArea rows={2} maxLength={10000} />
        </Form.Item>
        <Flex gap={16} wrap>
          <Form.Item name="monetaryImpact" label={t("monetaryImpact")}>
            <InputNumber min={0} style={{ width: 220 }} />
          </Form.Item>
          <Form.Item name="currency" label={t("currency")}>
            <Input maxLength={3} style={{ width: 90, textTransform: "uppercase" }} />
          </Form.Item>
        </Flex>
        <Form.Item name="recommendation" label={t("recommendation")}>
          <Input.TextArea rows={2} maxLength={10000} />
        </Form.Item>
        <Flex gap={16} wrap align="end">
          <Form.Item name="impact" label={t("impact")} extra={t("scaleHelp")}>
            <InputNumber min={1} max={5} />
          </Form.Item>
          <Form.Item name="likelihood" label={t("likelihood")}>
            <InputNumber min={1} max={5} />
          </Form.Item>
          <Form.Item label={t("risk")}>
            <Space>
              {preview && impact && likelihood ? (
                <RiskTag level={preview} score={impact * likelihood} />
              ) : (
                <Tag>{t("unrated")}</Tag>
              )}
            </Space>
          </Form.Item>
        </Flex>
        {!finding && source && (
          <Form.Item name="pageReference" label={t("pageReference")}>
            <Input maxLength={100} />
          </Form.Item>
        )}
      </Form>
    </Modal>
  );
}
