"use client";

import {
  ArrowLeftOutlined,
  DownloadOutlined,
  FileExcelOutlined,
  FilePdfOutlined,
  PlusOutlined,
  SendOutlined,
  UploadOutlined,
} from "@ant-design/icons";
import {
  Alert,
  App,
  Button,
  Card,
  DatePicker,
  Dropdown,
  Flex,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  Upload,
} from "antd";
import type { TableColumnsType } from "antd";
import dayjs, { type Dayjs } from "dayjs";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useFormatter, useTranslations } from "next-intl";
import { useMemo, useState, useTransition } from "react";

import type { Member, Project, Workstream } from "@/features/projects/types";
import { useUrlQuery } from "@/lib/navigation/useUrlQuery";

import { createItem, releaseWave, updateItem } from "./actions";
import { RequestItemDrawer } from "./RequestItemDrawer";
import {
  PRIORITIES,
  PRIORITY_COLORS,
  REQUEST_STATUSES,
  STATUS_COLORS,
  isOverdue,
  unreleasedByWave,
  waves,
  type ImportResult,
  type ItemInput,
  type RequestItem,
  type RequestPermissions,
  type RequestStatus,
} from "./types";

export interface RequestListViewProps {
  project: Project;
  items: RequestItem[];
  workstreams: Workstream[];
  targetMembers: Member[];
  permissions: RequestPermissions;
  filters: { status?: string; workstreamId?: string; wave?: string; q?: string };
  selectedItemId: string | null;
  today: string;
}

/** Request list of one project (PRD REQ-01…REQ-06): the internal team's workbench and the target's inbox. */
export function RequestListView({
  project,
  items,
  workstreams,
  targetMembers,
  permissions,
  filters,
  selectedItemId,
  today,
}: Readonly<RequestListViewProps>) {
  const t = useTranslations("requests");
  const tw = useTranslations("workstreams");
  const format = useFormatter();
  const { update } = useUrlQuery();
  const [editing, setEditing] = useState<RequestItem | "new" | null>(null);
  const [releasing, setReleasing] = useState(false);
  const [importing, setImporting] = useState(false);

  const counts = useMemo(() => {
    const result = Object.fromEntries(REQUEST_STATUSES.map((s) => [s, 0])) as Record<RequestStatus, number>;
    for (const item of items) result[item.status] += 1;
    return result;
  }, [items]);
  const overdue = items.filter((i) => isOverdue(i, today)).length;
  const filtered = Boolean(filters.status || filters.workstreamId || filters.wave || filters.q);

  const columns: TableColumnsType<RequestItem> = [
    { title: t("number"), dataIndex: "number", width: 110, fixed: "left" },
    {
      title: t("workstream"),
      dataIndex: "workstream",
      width: 130,
      render: (value: RequestItem["workstream"]) => (value ? tw(value) : null),
    },
    { title: t("category"), dataIndex: "category", width: 160, ellipsis: true },
    { title: t("description"), dataIndex: "description", ellipsis: true },
    {
      title: t("priority"),
      dataIndex: "priority",
      width: 100,
      render: (value: RequestItem["priority"]) => (
        <Tag color={PRIORITY_COLORS[value]}>{t(`priorityValues.${value}`)}</Tag>
      ),
    },
    {
      title: t("dueDate"),
      dataIndex: "dueDate",
      width: 120,
      render: (value: string | null, item) =>
        value ? (
          <Typography.Text type={isOverdue(item, today) ? "danger" : undefined}>
            {format.dateTime(new Date(`${value}T00:00:00`), { dateStyle: "medium" })}
          </Typography.Text>
        ) : null,
    },
    {
      title: t("status"),
      dataIndex: "status",
      width: 150,
      render: (value: RequestStatus) => <Tag color={STATUS_COLORS[value]}>{t(`statusValues.${value}`)}</Tag>,
    },
    { title: t("wave"), dataIndex: "wave", width: 110, align: "center" },
    { title: t("documents"), dataIndex: "documentCount", width: 100, align: "center" },
    {
      title: t("assignee"),
      dataIndex: "assigneeName",
      width: 150,
      ellipsis: true,
      render: (value: string | null) => value ?? t("unassigned"),
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
        <Space wrap>
          {permissions.edit && (
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setEditing("new")}>
              {t("newItem")}
            </Button>
          )}
          {permissions.release && (
            <Button
              icon={<SendOutlined />}
              onClick={() => setReleasing(true)}
              disabled={counts.NOT_REQUESTED === 0}
            >
              {t("release")}
            </Button>
          )}
          {permissions.edit && (
            <Button icon={<UploadOutlined />} onClick={() => setImporting(true)}>
              {t("import")}
            </Button>
          )}
          <Dropdown
            menu={{
              items: [
                {
                  key: "xlsx",
                  icon: <FileExcelOutlined />,
                  label: (
                    <a href={`/api/projects/${project.id}/requests/export?format=xlsx`}>{t("exportExcel")}</a>
                  ),
                },
                {
                  key: "pdf",
                  icon: <FilePdfOutlined />,
                  label: (
                    <a href={`/api/projects/${project.id}/requests/export?format=pdf`}>{t("exportPdf")}</a>
                  ),
                },
              ],
            }}
          >
            <Button icon={<DownloadOutlined />}>{t("export")}</Button>
          </Dropdown>
        </Space>
      </Flex>

      {permissions.deliver && (
        <Alert type="info" showIcon style={{ marginBottom: 16 }} title={t("targetHint")} />
      )}

      <Card>
        <Flex gap={8} wrap style={{ marginBottom: 16 }}>
          {REQUEST_STATUSES.filter((s) => counts[s] > 0).map((s) => (
            <Tag key={s} color={STATUS_COLORS[s]}>
              {t(`statusValues.${s}`)}: {counts[s]}
            </Tag>
          ))}
          {overdue > 0 && <Tag color="red">{t("overdueCount", { count: overdue })}</Tag>}
        </Flex>
        <Flex gap={8} wrap style={{ marginBottom: 16 }}>
          <Input.Search
            allowClear
            defaultValue={filters.q}
            placeholder={t("searchPlaceholder")}
            style={{ width: 260 }}
            onSearch={(q) => update({ q })}
          />
          <Select
            allowClear
            placeholder={t("status")}
            value={filters.status}
            style={{ width: 170 }}
            options={REQUEST_STATUSES.map((s) => ({ value: s, label: t(`statusValues.${s}`) }))}
            onChange={(status?: string) => update({ status })}
          />
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
            placeholder={t("wave")}
            value={filters.wave}
            style={{ width: 120 }}
            options={waves(items).map((w) => ({ value: String(w), label: t("waveN", { wave: w }) }))}
            onChange={(wave?: string) => update({ wave })}
          />
        </Flex>
        <Table<RequestItem>
          rowKey="id"
          size="middle"
          columns={columns}
          dataSource={items}
          scroll={{ x: 1200 }}
          pagination={{ pageSize: 50, hideOnSinglePage: true, showSizeChanger: false }}
          locale={{
            emptyText: filtered ? t("noMatches") : permissions.deliver ? t("emptyTarget") : t("empty"),
          }}
          onRow={(item) => ({
            onClick: () => update({ item: item.id }, false),
            style: { cursor: "pointer" },
          })}
        />
      </Card>

      <RequestItemDrawer
        project={project}
        itemId={selectedItemId}
        permissions={permissions}
        onEdit={setEditing}
        onClose={() => update({ item: undefined }, false)}
      />
      {editing && (
        <ItemFormModal
          projectId={project.id}
          item={editing === "new" ? null : editing}
          workstreams={workstreams}
          targetMembers={targetMembers}
          onClose={() => setEditing(null)}
        />
      )}
      {releasing && (
        <ReleaseModal
          projectId={project.id}
          items={items}
          workstreams={workstreams}
          onClose={() => setReleasing(false)}
        />
      )}
      {importing && <ImportModal projectId={project.id} onClose={() => setImporting(false)} />}
    </>
  );
}

interface ItemFormValues {
  workstreamId: string;
  number?: string;
  category?: string;
  description: string;
  priority: RequestItem["priority"];
  dueDate?: Dayjs | null;
  wave: number;
  assigneeUserId?: string | null;
}

function ItemFormModal({
  projectId,
  item,
  workstreams,
  targetMembers,
  onClose,
}: Readonly<{
  projectId: string;
  item: RequestItem | null;
  workstreams: Workstream[];
  targetMembers: Member[];
  onClose: () => void;
}>) {
  const t = useTranslations("requests");
  const tw = useTranslations("workstreams");
  const { message } = App.useApp();
  const router = useRouter();
  const [form] = Form.useForm<ItemFormValues>();
  const [pending, startTransition] = useTransition();
  const workstreamId = Form.useWatch("workstreamId", form);
  const assignees = targetMembers.filter((m) => m.workstreamId === null || m.workstreamId === workstreamId);

  const submit = (values: ItemFormValues) =>
    startTransition(async () => {
      const input: ItemInput = {
        workstreamId: values.workstreamId,
        number: values.number?.trim() || undefined,
        category: values.category,
        description: values.description,
        priority: values.priority,
        dueDate: values.dueDate ? values.dueDate.format("YYYY-MM-DD") : null,
        wave: values.wave,
        assigneeUserId: values.assigneeUserId ?? null,
      };
      const result = item ? await updateItem(projectId, item.id, input) : await createItem(projectId, input);
      if (!result.ok) {
        message.error(result.message || t("error"));
        return;
      }
      message.success(item ? t("saved") : t("created", { number: result.data?.number ?? "" }));
      router.refresh();
      onClose();
    });

  return (
    <Modal
      open
      title={item ? t("editTitle", { number: item.number }) : t("newItem")}
      okText={item ? t("save") : t("create")}
      confirmLoading={pending}
      onOk={() => form.submit()}
      onCancel={onClose}
      destroyOnHidden
    >
      <Form<ItemFormValues>
        form={form}
        layout="vertical"
        onFinish={submit}
        initialValues={
          item
            ? {
                workstreamId: item.workstreamId,
                category: item.category,
                description: item.description,
                priority: item.priority,
                dueDate: item.dueDate ? dayjs(item.dueDate) : null,
                wave: item.wave,
                assigneeUserId: item.assigneeUserId,
              }
            : { priority: "MEDIUM", wave: 1, workstreamId: workstreams[0]?.id }
        }
      >
        <Form.Item name="workstreamId" label={t("workstream")} rules={[{ required: true }]}>
          <Select options={workstreams.map((w) => ({ value: w.id, label: tw(w.type) }))} />
        </Form.Item>
        {!item && (
          <Form.Item name="number" label={t("number")} extra={t("numberHelp")}>
            <Input maxLength={32} />
          </Form.Item>
        )}
        <Form.Item name="category" label={t("category")}>
          <Input maxLength={200} />
        </Form.Item>
        <Form.Item name="description" label={t("description")} rules={[{ required: true, whitespace: true }]}>
          <Input.TextArea rows={4} maxLength={2000} showCount />
        </Form.Item>
        <Flex gap={16} wrap>
          <Form.Item name="priority" label={t("priority")} style={{ minWidth: 140 }}>
            <Select options={PRIORITIES.map((p) => ({ value: p, label: t(`priorityValues.${p}`) }))} />
          </Form.Item>
          <Form.Item name="dueDate" label={t("dueDate")}>
            <DatePicker />
          </Form.Item>
          <Form.Item name="wave" label={t("wave")}>
            <InputNumber min={1} max={20} />
          </Form.Item>
        </Flex>
        <Form.Item name="assigneeUserId" label={t("assignee")} extra={t("assigneeHelp")}>
          <Select
            allowClear
            options={assignees.map((m) => ({ value: m.userId, label: m.displayName ?? m.email ?? m.userId }))}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
}

function ReleaseModal({
  projectId,
  items,
  workstreams,
  onClose,
}: Readonly<{ projectId: string; items: RequestItem[]; workstreams: Workstream[]; onClose: () => void }>) {
  const t = useTranslations("requests");
  const tw = useTranslations("workstreams");
  const { message } = App.useApp();
  const router = useRouter();
  const pending = unreleasedByWave(items);
  const [wave, setWave] = useState<number | undefined>(pending.keys().next().value);
  const [workstreamId, setWorkstreamId] = useState<string | undefined>();
  const [busy, startTransition] = useTransition();
  const count = items.filter(
    (i) =>
      i.status === "NOT_REQUESTED" && i.wave === wave && (!workstreamId || i.workstreamId === workstreamId),
  ).length;

  const release = () =>
    startTransition(async () => {
      if (wave === undefined) return;
      const result = await releaseWave(projectId, wave, workstreamId);
      if (!result.ok) {
        message.error(result.message || t("error"));
        return;
      }
      message.success(t("released", { count: result.data?.released ?? 0 }));
      router.refresh();
      onClose();
    });

  return (
    <Modal
      open
      title={t("releaseTitle")}
      okText={t("releaseCount", { count })}
      okButtonProps={{ disabled: count === 0 }}
      confirmLoading={busy}
      onOk={release}
      onCancel={onClose}
    >
      <Typography.Paragraph type="secondary">{t("releaseHelp")}</Typography.Paragraph>
      <Flex vertical gap={12}>
        <Select
          value={wave}
          onChange={setWave}
          options={[...pending.entries()].map(([w, n]) => ({
            value: w,
            label: t("waveCount", { wave: w, count: n }),
          }))}
        />
        <Select
          allowClear
          placeholder={t("allWorkstreams")}
          value={workstreamId}
          onChange={setWorkstreamId}
          options={workstreams.map((w) => ({ value: w.id, label: tw(w.type) }))}
        />
      </Flex>
    </Modal>
  );
}

function ImportModal({ projectId, onClose }: Readonly<{ projectId: string; onClose: () => void }>) {
  const t = useTranslations("requests");
  const { message } = App.useApp();
  const router = useRouter();
  const [file, setFile] = useState<File | null>(null);
  const [result, setResult] = useState<ImportResult | null>(null);
  const [busy, setBusy] = useState(false);

  const send = async (selected: File, dryRun: boolean) => {
    setBusy(true);
    try {
      const body = new FormData();
      body.append("file", selected);
      const response = await fetch(
        `/api/projects/${encodeURIComponent(projectId)}/requests/import?dryRun=${dryRun}`,
        { method: "POST", body },
      );
      const payload = (await response.json().catch(() => ({}))) as ImportResult & { detail?: string };
      if (!response.ok) {
        message.error(payload.detail || t("error"));
        setResult(null);
        return;
      }
      setResult(payload);
      if (payload.committed) {
        message.success(t("imported", { created: payload.created, updated: payload.updated }));
        router.refresh();
        onClose();
      }
    } finally {
      setBusy(false);
    }
  };

  const invalidRows = result?.rows.filter((r) => r.errors.length > 0) ?? [];
  return (
    <Modal
      open
      width={720}
      title={t("importTitle")}
      okText={t("importConfirm", { count: (result?.created ?? 0) + (result?.updated ?? 0) })}
      okButtonProps={{ disabled: !file || !result || result.invalid > 0 }}
      confirmLoading={busy}
      onOk={() => file && void send(file, false)}
      onCancel={onClose}
    >
      <Flex vertical gap={12}>
        <Typography.Paragraph type="secondary" style={{ margin: 0 }}>
          {t("importHelp")}{" "}
          <a href={`/api/projects/${projectId}/requests/import-template`}>{t("downloadTemplate")}</a>
        </Typography.Paragraph>
        <Upload
          accept=".xlsx"
          maxCount={1}
          beforeUpload={(selected) => {
            setFile(selected);
            void send(selected, true);
            return false;
          }}
          onRemove={() => {
            setFile(null);
            setResult(null);
          }}
        >
          <Button icon={<UploadOutlined />} loading={busy}>
            {t("chooseFile")}
          </Button>
        </Upload>
        {result && (
          <Alert
            type={result.invalid > 0 ? "error" : "success"}
            showIcon
            title={
              result.invalid > 0
                ? t("importInvalid", { count: result.invalid })
                : t("importPreview", { created: result.created, updated: result.updated })
            }
          />
        )}
        {invalidRows.length > 0 && (
          <Table
            size="small"
            rowKey="row"
            pagination={{ pageSize: 10, hideOnSinglePage: true }}
            dataSource={invalidRows}
            columns={[
              { title: t("row"), dataIndex: "row", width: 70 },
              { title: t("number"), dataIndex: "number", width: 110 },
              {
                title: t("problems"),
                dataIndex: "errors",
                render: (errors: string[]) => errors.join("; "),
              },
            ]}
          />
        )}
      </Flex>
    </Modal>
  );
}
