"use client";

import {
  AlertOutlined,
  ArrowLeftOutlined,
  ClockCircleOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EditOutlined,
  FileTextOutlined,
  FolderOpenOutlined,
  PlusOutlined,
  QuestionCircleOutlined,
  UnorderedListOutlined,
} from "@ant-design/icons";
import {
  Alert,
  App,
  Button,
  Card,
  Col,
  DatePicker,
  Descriptions,
  Flex,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Progress,
  Row,
  Select,
  Space,
  Statistic,
  Table,
  Tabs,
  Tag,
  Typography,
} from "antd";
import dayjs, { type Dayjs } from "dayjs";
import Link from "next/link";
import { useFormatter, useTranslations } from "next-intl";
import { useState, useTransition } from "react";

import { riskColors } from "@/theme/themeConfig";

import {
  addMember,
  createTimeEntry,
  deleteTimeEntry,
  purgeProjectData,
  removeMember,
  replaceWorkstreams,
  searchUsers,
  setProjectArchived,
  updateProject,
  updateTimeEntry,
} from "./actions";
import {
  MANAGER_ROLES,
  RISK_LEVELS,
  STAGES,
  WORKSTREAMS,
  allowedProjectRoles,
  type ActionResult,
  type Dashboard,
  type Member,
  type Project,
  type ProjectRole,
  type ProjectStage,
  type TimeEntry,
  type TimeEntrySummary,
  type UserSummary,
  type Workstream,
  type WorkstreamType,
} from "./types";

export interface ProjectDetailViewProps {
  project: Project;
  workstreams: Workstream[];
  members: Member[];
  dashboard: Dashboard;
  timeEntries?: TimeEntry[];
  timeEntrySummary?: TimeEntrySummary | null;
}

export function ProjectDetailView({
  project,
  workstreams,
  members,
  dashboard,
  timeEntries = [],
  timeEntrySummary = null,
}: Readonly<ProjectDetailViewProps>) {
  const t = useTranslations();
  const format = useFormatter();
  const canManage = project.myRoles.some((role) => MANAGER_ROLES.includes(role));
  const editable = canManage && project.status === "ACTIVE";
  const isConsultant = project.myRoles.some((role) =>
    ["PARTNER", "PROJECT_MANAGER", "ANALYST", "OBSERVER"].includes(role),
  );

  return (
    <>
      <Flex vertical gap={4} style={{ marginBottom: 16 }}>
        <Link href="/">
          <ArrowLeftOutlined /> {t("projects.title")}
        </Link>
        <Flex align="center" gap={12} wrap>
          <Typography.Title level={3} style={{ margin: 0 }}>
            {project.name}
          </Typography.Title>
          <Tag>{project.code}</Tag>
          <Tag color={project.status === "ACTIVE" ? "green" : "default"}>
            {t(`projects.statusValues.${project.status}`)}
          </Tag>
          <Tag color="blue">{t(`stages.${project.stage}`)}</Tag>
          {!project.myRoles.every((role) => role === "CLIENT") && (
            <>
              <Link href={`/projects/${project.id}/requests`}>
                <Button icon={<UnorderedListOutlined />}>{t("nav.requests")}</Button>
              </Link>
              <Link href={`/projects/${project.id}/data-room`}>
                <Button icon={<FolderOpenOutlined />}>{t("dataRoom.open")}</Button>
              </Link>
            </>
          )}
          {!project.myRoles.every((role) => role === "TARGET") && (
            <Link href={`/projects/${project.id}/findings`}>
              <Button icon={<AlertOutlined />}>{t("nav.findings")}</Button>
            </Link>
          )}
          <Link href={`/projects/${project.id}/qna`}>
            <Button icon={<QuestionCircleOutlined />}>{t("nav.qna")}</Button>
          </Link>
          {!project.myRoles.every((role) => role === "TARGET") && (
            <Link href={`/projects/${project.id}/reports`}>
              <Button icon={<FileTextOutlined />}>{t("nav.reports")}</Button>
            </Link>
          )}
        </Flex>
        <Typography.Text type="secondary">
          {project.targetName} · {project.clientName}
        </Typography.Text>
      </Flex>
      {project.status === "ARCHIVED" && (
        <Alert
          type="info"
          showIcon
          title={t("project.archivedNotice")}
          description={
            project.retentionDeadline
              ? t("project.purgeScheduled", {
                  date: format.dateTime(new Date(project.retentionDeadline), { dateStyle: "medium" }),
                })
              : undefined
          }
          style={{ marginBottom: 16 }}
        />
      )}
      {project.status === "PURGED" && (
        <Alert type="warning" showIcon title={t("project.purgedNotice")} style={{ marginBottom: 16 }} />
      )}
      <Tabs
        items={[
          {
            key: "overview",
            label: t("project.overview"),
            children: <DashboardTab dashboard={dashboard} />,
          },
          {
            key: "team",
            label: t("project.team"),
            children: (
              <TeamTab project={project} workstreams={workstreams} members={members} editable={editable} />
            ),
          },
          ...(isConsultant
            ? [
                {
                  key: "timesheet",
                  label: t("project.timesheet"),
                  children: (
                    <TimesheetTab
                      project={project}
                      workstreams={workstreams}
                      timeEntries={timeEntries}
                      summary={timeEntrySummary}
                      editable={editable}
                    />
                  ),
                },
              ]
            : []),
          {
            key: "settings",
            label: t("project.settings"),
            children: (
              <SettingsTab
                project={project}
                workstreams={workstreams}
                canManage={canManage}
                editable={editable}
              />
            ),
          },
        ]}
      />
    </>
  );
}

function DashboardTab({ dashboard }: Readonly<{ dashboard: Dashboard }>) {
  const t = useTranslations();
  return (
    <Flex vertical gap={16}>
      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} lg={6}>
          <Card>
            <Statistic title={t("project.completion")} value={dashboard.completionPercent} suffix="%" />
            <Typography.Text type="secondary">{t("project.completionHelp")}</Typography.Text>
          </Card>
        </Col>
        <Col xs={12} lg={6}>
          <Card>
            <Statistic title={t("project.dueSoon")} value={dashboard.dueSoon} />
          </Card>
        </Col>
        <Col xs={12} lg={6}>
          <Card>
            <Statistic
              title={t("project.overdue")}
              value={dashboard.overdue}
              styles={{ content: dashboard.overdue > 0 ? { color: riskColors.CRITICAL } : undefined }}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card>
            <Statistic title={t("project.members")} value={dashboard.memberCount} />
          </Card>
        </Col>
      </Row>
      <Row gutter={[16, 16]}>
        <Col xs={24} lg={14}>
          <Card title={t("project.requestProgress")}>
            {dashboard.workstreams.every((w) => w.total === 0) && (
              <Typography.Paragraph type="secondary">{t("project.noRequests")}</Typography.Paragraph>
            )}
            <Flex vertical gap={12}>
              {dashboard.workstreams.map((w) => (
                <div key={w.workstreamId}>
                  <Flex justify="space-between">
                    <Typography.Text>{t(`workstreams.${w.type}`)}</Typography.Text>
                    <Typography.Text type="secondary">
                      {t("project.itemsSummary", { fulfilled: w.fulfilled, requested: w.requested })}
                    </Typography.Text>
                  </Flex>
                  <Progress percent={w.completionPercent} />
                </div>
              ))}
            </Flex>
          </Card>
        </Col>
        <Col xs={24} lg={10}>
          <Card title={t("project.findingsByRisk")}>
            <Flex vertical gap={8}>
              {RISK_LEVELS.map((level) => (
                <Flex key={level} justify="space-between" align="center">
                  <Space>
                    <Tag color={level === "UNRATED" ? "default" : riskColors[level]}>
                      {t(`risk.${level}`)}
                    </Tag>
                  </Space>
                  <Typography.Text strong>{dashboard.findingsByRisk[level] ?? 0}</Typography.Text>
                </Flex>
              ))}
            </Flex>
          </Card>
        </Col>
      </Row>
    </Flex>
  );
}

function useActionFeedback() {
  const t = useTranslations();
  const { message } = App.useApp();
  return (result: ActionResult<unknown>, success = t("common.saved")) => {
    if (result.ok) {
      message.success(success);
    } else {
      message.error(result.message || t("common.error"));
    }
    return result.ok;
  };
}

interface AddMemberForm {
  userId: string;
  role: ProjectRole;
  workstreamId?: string;
  accessExpiresAt?: Dayjs;
}

function TeamTab({
  project,
  workstreams,
  members,
  editable,
}: Readonly<{ project: Project; workstreams: Workstream[]; members: Member[]; editable: boolean }>) {
  const t = useTranslations();
  const format = useFormatter();
  const feedback = useActionFeedback();
  const [open, setOpen] = useState(false);
  const [pending, startTransition] = useTransition();
  const [options, setOptions] = useState<UserSummary[]>([]);
  const [form] = Form.useForm<AddMemberForm>();
  const selectedUserId = Form.useWatch("userId", form);
  const selectedUser = options.find((u) => u.id === selectedUserId);
  const workstreamName = new Map(workstreams.map((w) => [w.id, t(`workstreams.${w.type}`)]));

  const search = (q: string) =>
    startTransition(async () => {
      // External users can only come from the project's own client or target organization.
      const [internal, client, target] = await Promise.all([
        searchUsers(q),
        searchUsers(q, project.clientOrganizationId),
        searchUsers(q, project.targetOrganizationId),
      ]);
      const eligible = [
        ...internal.filter((u) => !["CLIENT", "TARGET"].includes(u.role)),
        ...client,
        ...target,
      ];
      setOptions(eligible.filter((u, i, all) => all.findIndex((x) => x.id === u.id) === i));
    });

  const submit = (values: AddMemberForm) =>
    startTransition(async () => {
      const ok = feedback(
        await addMember(project.id, {
          userId: values.userId,
          role: values.role,
          workstreamId: values.workstreamId,
          accessExpiresAt: values.accessExpiresAt?.endOf("day").toISOString(),
        }),
      );
      if (ok) {
        setOpen(false);
        form.resetFields();
      }
    });

  return (
    <>
      <Flex justify="space-between" align="center" style={{ marginBottom: 12 }}>
        <Typography.Text type="secondary">{t("project.externalHint")}</Typography.Text>
        {editable && (
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>
            {t("project.addMember")}
          </Button>
        )}
      </Flex>
      <Table<Member>
        rowKey="id"
        dataSource={members}
        loading={pending}
        pagination={false}
        scroll={{ x: 800 }}
        columns={[
          {
            title: t("common.name"),
            key: "name",
            render: (_, m) => (
              <Flex vertical>
                <Typography.Text strong>{m.displayName ?? m.userId}</Typography.Text>
                <Typography.Text type="secondary">{m.email}</Typography.Text>
              </Flex>
            ),
          },
          {
            title: t("project.projectRole"),
            key: "role",
            render: (_, m) => <Tag>{t(`projectRoles.${m.role}`)}</Tag>,
          },
          {
            title: t("project.workstream"),
            key: "workstream",
            render: (_, m) =>
              m.workstreamId
                ? (workstreamName.get(m.workstreamId) ?? t("common.none"))
                : t("project.allWorkstreams"),
          },
          {
            title: t("project.accessUntil"),
            key: "access",
            render: (_, m) =>
              m.accessExpiresAt
                ? format.dateTime(new Date(m.accessExpiresAt), { dateStyle: "medium" })
                : t("common.none"),
          },
          ...(editable
            ? [
                {
                  title: t("common.actions"),
                  key: "actions",
                  render: (_: unknown, m: Member) => (
                    <Popconfirm
                      title={t("project.removeConfirm")}
                      okText={t("common.yes")}
                      cancelText={t("common.no")}
                      onConfirm={() =>
                        startTransition(async () => {
                          feedback(await removeMember(project.id, m.id));
                        })
                      }
                    >
                      <Button size="small" danger>
                        {t("project.remove")}
                      </Button>
                    </Popconfirm>
                  ),
                },
              ]
            : []),
        ]}
      />
      <Modal
        title={t("project.addMember")}
        open={open}
        okText={t("common.save")}
        cancelText={t("common.cancel")}
        confirmLoading={pending}
        onOk={() => form.submit()}
        onCancel={() => setOpen(false)}
        destroyOnHidden
      >
        <Form<AddMemberForm> form={form} layout="vertical" onFinish={submit}>
          <Form.Item name="userId" label={t("project.user")} rules={[{ required: true }]}>
            <Select
              showSearch={{ filterOption: false, onSearch: search }}
              placeholder={t("project.userSearch")}
              notFoundContent={null}
              onFocus={() => options.length === 0 && search("")}
              onChange={() => form.setFieldValue("role", undefined)}
              options={options.map((u) => ({
                value: u.id,
                label: `${u.displayName} <${u.email}> · ${t(`roles.${u.role}`)}`,
              }))}
            />
          </Form.Item>
          <Form.Item name="role" label={t("project.projectRole")} rules={[{ required: true }]}>
            <Select
              disabled={!selectedUser}
              options={(selectedUser ? allowedProjectRoles(selectedUser.role) : []).map((role) => ({
                value: role,
                label: t(`projectRoles.${role}`),
              }))}
            />
          </Form.Item>
          <Form.Item name="workstreamId" label={t("project.workstream")}>
            <Select
              allowClear
              placeholder={t("project.allWorkstreams")}
              options={workstreams.map((w) => ({ value: w.id, label: t(`workstreams.${w.type}`) }))}
            />
          </Form.Item>
          <Form.Item name="accessExpiresAt" label={t("project.accessUntil")}>
            <DatePicker style={{ width: "100%" }} disabledDate={(d) => d.isBefore(dayjs(), "day")} />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}

interface SettingsForm {
  name: string;
  stage: ProjectStage;
  period?: [Dayjs, Dayjs];
  workstreams: WorkstreamType[];
  retentionPeriodDays: number;
  allowedIpRanges: string;
}

function SettingsTab({
  project,
  workstreams,
  canManage,
  editable,
}: Readonly<{ project: Project; workstreams: Workstream[]; canManage: boolean; editable: boolean }>) {
  const t = useTranslations();
  const format = useFormatter();
  const feedback = useActionFeedback();
  const [pending, startTransition] = useTransition();
  const [form] = Form.useForm<SettingsForm>();

  if (!canManage) {
    return (
      <Card>
        <Descriptions column={1}>
          <Descriptions.Item label={t("projects.workstreams")}>
            <Space wrap>
              {workstreams.map((w) => (
                <Tag key={w.id}>{t(`workstreams.${w.type}`)}</Tag>
              ))}
            </Space>
          </Descriptions.Item>
          <Descriptions.Item label={t("project.ipAllowList")}>
            {project.allowedIpRanges?.length
              ? project.allowedIpRanges.join(", ")
              : t("project.ipAllowListNone")}
          </Descriptions.Item>
          <Descriptions.Item label={t("project.retentionSchedule")}>
            {project.retentionPeriodDays} {t("common.days")}
            {project.retentionDeadline && (
              <Typography.Text type="secondary" style={{ marginLeft: 8 }}>
                (
                {t("project.purgeScheduled", {
                  date: format.dateTime(new Date(project.retentionDeadline), { dateStyle: "medium" }),
                })}
                )
              </Typography.Text>
            )}
          </Descriptions.Item>
        </Descriptions>
        <Typography.Text type="secondary">{t("project.readOnly")}</Typography.Text>
      </Card>
    );
  }

  const initial: SettingsForm = {
    name: project.name,
    stage: project.stage,
    period:
      project.startDate && project.endDate ? [dayjs(project.startDate), dayjs(project.endDate)] : undefined,
    workstreams: workstreams.map((w) => w.type),
    retentionPeriodDays: project.retentionPeriodDays || 365,
    allowedIpRanges: (project.allowedIpRanges ?? []).join("\n"),
  };

  const save = (values: SettingsForm) =>
    startTransition(async () => {
      const updated = await updateProject(project.id, {
        name: values.name,
        stage: values.stage,
        startDate: values.period?.[0].format("YYYY-MM-DD"),
        endDate: values.period?.[1].format("YYYY-MM-DD"),
        retentionPeriodDays: values.retentionPeriodDays,
        allowedIpRanges: values.allowedIpRanges
          .split(/[\s,]+/)
          .map((range) => range.trim())
          .filter(Boolean),
      });
      const changed = [...values.workstreams].sort().join() !== [...initial.workstreams].sort().join();
      const result =
        updated.ok && changed ? await replaceWorkstreams(project.id, values.workstreams) : updated;
      feedback(result);
    });

  return (
    <Card>
      <Form<SettingsForm>
        form={form}
        layout="vertical"
        initialValues={initial}
        onFinish={save}
        disabled={!editable}
      >
        <Form.Item name="name" label={t("projects.name")} rules={[{ required: true, max: 200 }]}>
          <Input />
        </Form.Item>
        <Flex gap={12} wrap>
          <Form.Item name="stage" label={t("projects.stage")} style={{ minWidth: 240 }}>
            <Select options={STAGES.map((s) => ({ value: s, label: t(`stages.${s}`) }))} />
          </Form.Item>
          <Form.Item name="period" label={t("projects.period")}>
            <DatePicker.RangePicker />
          </Form.Item>
        </Flex>
        <Form.Item
          name="workstreams"
          label={t("projects.workstreams")}
          rules={[{ required: true, type: "array", min: 1 }]}
        >
          <Select
            mode="multiple"
            options={WORKSTREAMS.map((w) => ({ value: w, label: t(`workstreams.${w}`) }))}
          />
        </Form.Item>
        <Form.Item
          name="retentionPeriodDays"
          label={t("projects.retentionDays")}
          extra={t("projects.retentionDaysHelp")}
        >
          <Select
            options={[
              { value: 30, label: "30 " + t("common.days") },
              { value: 90, label: "90 " + t("common.days") },
              { value: 180, label: "180 " + t("common.days") },
              { value: 365, label: "365 " + t("common.days") },
              { value: 730, label: "730 " + t("common.days") },
            ]}
          />
        </Form.Item>
        <Form.Item
          name="allowedIpRanges"
          label={t("project.ipAllowList")}
          extra={t("project.ipAllowListHelp")}
        >
          <Input.TextArea rows={3} placeholder={"203.0.113.0/24\n2001:db8::/32"} spellCheck={false} />
        </Form.Item>
        <Flex justify="space-between" wrap gap={12}>
          <Button type="primary" htmlType="submit" loading={pending}>
            {t("project.save")}
          </Button>
        </Flex>
      </Form>
      <Flex justify="end" gap={12} style={{ marginTop: 16 }}>
        {project.status === "ARCHIVED" && !project.purgedAt && (
          <Popconfirm
            title={t("project.purgeConfirm")}
            okText={t("common.yes")}
            cancelText={t("common.no")}
            onConfirm={() =>
              startTransition(async () => {
                feedback(await purgeProjectData(project.id));
              })
            }
          >
            <Button danger>{t("project.purgeNow")}</Button>
          </Popconfirm>
        )}
        {project.status === "ACTIVE" ? (
          <Popconfirm
            title={t("project.archiveConfirm")}
            okText={t("common.yes")}
            cancelText={t("common.no")}
            onConfirm={() =>
              startTransition(async () => {
                feedback(await setProjectArchived(project.id, true));
              })
            }
          >
            <Button danger>{t("project.archive")}</Button>
          </Popconfirm>
        ) : project.status === "ARCHIVED" ? (
          <Button
            onClick={() =>
              startTransition(async () => {
                feedback(await setProjectArchived(project.id, false));
              })
            }
          >
            {t("project.reactivate")}
          </Button>
        ) : (
          <Tag color="default">{t("projects.statusValues.PURGED")}</Tag>
        )}
      </Flex>
    </Card>
  );
}

interface TimesheetFormValues {
  workstreamType: WorkstreamType;
  entryDate: Dayjs;
  hours: number;
  description?: string;
}

function TimesheetTab({
  project,
  workstreams,
  timeEntries = [],
  summary,
  editable,
}: Readonly<{
  project: Project;
  workstreams: Workstream[];
  timeEntries?: TimeEntry[];
  summary?: TimeEntrySummary | null;
  editable: boolean;
}>) {
  const t = useTranslations();
  const format = useFormatter();
  const feedback = useActionFeedback();
  const [pending, startTransition] = useTransition();
  const [modalOpen, setModalOpen] = useState(false);
  const [editingEntry, setEditingEntry] = useState<TimeEntry | null>(null);
  const [form] = Form.useForm<TimesheetFormValues>();

  const openCreateModal = () => {
    setEditingEntry(null);
    form.resetFields();
    form.setFieldsValue({
      entryDate: dayjs(),
      workstreamType: workstreams[0]?.type ?? "FINANCIAL",
      hours: 1.0,
      description: "",
    });
    setModalOpen(true);
  };

  const openEditModal = (entry: TimeEntry) => {
    setEditingEntry(entry);
    form.setFieldsValue({
      entryDate: dayjs(entry.entryDate),
      workstreamType: entry.workstreamType,
      hours: entry.hours,
      description: entry.description ?? "",
    });
    setModalOpen(true);
  };

  const handleSave = (values: TimesheetFormValues) => {
    startTransition(async () => {
      const payload = {
        workstreamType: values.workstreamType,
        entryDate: values.entryDate.format("YYYY-MM-DD"),
        hours: Number(values.hours),
        description: values.description,
      };
      const result = editingEntry
        ? await updateTimeEntry(project.id, editingEntry.id, payload)
        : await createTimeEntry(project.id, payload);
      feedback(result);
      if (result.ok) {
        setModalOpen(false);
      }
    });
  };

  const handleDelete = (entryId: string) => {
    startTransition(async () => {
      const result = await deleteTimeEntry(project.id, entryId);
      feedback(result);
    });
  };

  return (
    <Flex vertical gap={16}>
      <Flex justify="space-between" align="center" wrap gap={8}>
        <Typography.Title level={4} style={{ margin: 0 }}>
          {t("project.timesheetTitle")}
        </Typography.Title>
        <Space>
          <a href={`/api/projects/${project.id}/time-entries/export`} download>
            <Button icon={<DownloadOutlined />}>{t("project.exportTimesheet")}</Button>
          </a>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreateModal} disabled={!editable}>
            {t("project.logTime")}
          </Button>
        </Space>
      </Flex>

      {summary && (
        <Row gutter={[16, 16]}>
          <Col xs={24} sm={8}>
            <Card>
              <Statistic
                title={t("project.totalLoggedHours")}
                value={summary.totalHours}
                suffix="hrs"
                prefix={<ClockCircleOutlined />}
              />
            </Card>
          </Col>
          <Col xs={24} sm={16}>
            <Card title={t("project.hoursByWorkstream")}>
              <Space wrap size="large">
                {workstreams.map((w) => {
                  const hrs = summary.byWorkstream[w.type] ?? 0;
                  return <Statistic key={w.id} title={t(`workstreams.${w.type}`)} value={hrs} suffix="hrs" />;
                })}
              </Space>
            </Card>
          </Col>
        </Row>
      )}

      <Card title={t("project.timeEntriesList")}>
        <Table<TimeEntry>
          dataSource={timeEntries}
          rowKey="id"
          pagination={{ pageSize: 10 }}
          columns={[
            {
              title: t("project.entryDate"),
              dataIndex: "entryDate",
              key: "entryDate",
              render: (d: string) => format.dateTime(new Date(d), { dateStyle: "medium" }),
            },
            {
              title: t("project.consultant"),
              dataIndex: "userName",
              key: "userName",
            },
            {
              title: t("projects.workstreams"),
              dataIndex: "workstreamType",
              key: "workstreamType",
              render: (w: WorkstreamType) => <Tag>{t(`workstreams.${w}`)}</Tag>,
            },
            {
              title: t("project.hours"),
              dataIndex: "hours",
              key: "hours",
              render: (h: number) => `${h} hrs`,
            },
            {
              title: t("project.description"),
              dataIndex: "description",
              key: "description",
              render: (desc: string | null) => desc || "—",
            },
            {
              title: t("common.actions"),
              key: "actions",
              render: (_, record) => (
                <Space>
                  <Button
                    size="small"
                    icon={<EditOutlined />}
                    onClick={() => openEditModal(record)}
                    disabled={!editable}
                  />
                  <Popconfirm
                    title={t("common.confirmDelete")}
                    onConfirm={() => handleDelete(record.id)}
                    disabled={!editable}
                  >
                    <Button
                      size="small"
                      danger
                      icon={<DeleteOutlined />}
                      loading={pending}
                      disabled={!editable}
                    />
                  </Popconfirm>
                </Space>
              ),
            },
          ]}
        />
      </Card>

      <Modal
        title={editingEntry ? t("project.editTimeEntry") : t("project.logTime")}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={pending}
        destroyOnClose
      >
        <Form<TimesheetFormValues> form={form} layout="vertical" onFinish={handleSave}>
          <Form.Item name="workstreamType" label={t("projects.workstreams")} rules={[{ required: true }]}>
            <Select
              options={workstreams.map((w) => ({
                value: w.type,
                label: t(`workstreams.${w.type}`),
              }))}
            />
          </Form.Item>
          <Form.Item name="entryDate" label={t("project.entryDate")} rules={[{ required: true }]}>
            <DatePicker style={{ width: "100%" }} />
          </Form.Item>
          <Form.Item
            name="hours"
            label={t("project.hours")}
            rules={[{ required: true, min: 0.25, max: 24.0, type: "number" }]}
          >
            <InputNumber min={0.25} max={24.0} step={0.5} style={{ width: "100%" }} />
          </Form.Item>
          <Form.Item name="description" label={t("project.description")}>
            <Input.TextArea rows={3} maxLength={2000} showCount />
          </Form.Item>
        </Form>
      </Modal>
    </Flex>
  );
}
