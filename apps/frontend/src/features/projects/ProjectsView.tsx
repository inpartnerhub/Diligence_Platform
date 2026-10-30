"use client";

import { PlusOutlined } from "@ant-design/icons";
import {
  App,
  Button,
  DatePicker,
  Drawer,
  Empty,
  Flex,
  Form,
  Input,
  Select,
  Table,
  Tag,
  Typography,
} from "antd";
import type { Dayjs } from "dayjs";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useFormatter, useTranslations } from "next-intl";
import { useState, useTransition } from "react";

import { PageHeader } from "@/components/PageHeader";
import type { Page } from "@/features/admin/types";
import { useUrlQuery } from "@/lib/navigation/useUrlQuery";

import { createProject } from "./actions";
import {
  WORKSTREAMS,
  type OrganizationSummary,
  type Project,
  type ProjectTemplate,
  type WorkstreamType,
} from "./types";

interface CreateForm {
  code: string;
  name: string;
  clientOrganizationId: string;
  targetOrganizationId: string;
  period?: [Dayjs, Dayjs];
  templateId?: string;
  workstreams?: WorkstreamType[];
}

export interface ProjectsViewProps {
  projects: Page<Project>;
  canCreate: boolean;
  clients: OrganizationSummary[];
  targets: OrganizationSummary[];
  templates: ProjectTemplate[];
}

export function ProjectsView({
  projects,
  canCreate,
  clients,
  targets,
  templates,
}: Readonly<ProjectsViewProps>) {
  const t = useTranslations();
  const format = useFormatter();
  const router = useRouter();
  const { message } = App.useApp();
  const { params, update } = useUrlQuery();
  const [open, setOpen] = useState(false);
  const [pending, startTransition] = useTransition();
  const [form] = Form.useForm<CreateForm>();
  const templateId = Form.useWatch("templateId", form);

  const formatDate = (value: string | null) =>
    value ? format.dateTime(new Date(value), { dateStyle: "medium" }) : t("common.none");

  const submit = (values: CreateForm) =>
    startTransition(async () => {
      const result = await createProject({
        code: values.code,
        name: values.name,
        clientOrganizationId: values.clientOrganizationId,
        targetOrganizationId: values.targetOrganizationId,
        startDate: values.period?.[0].format("YYYY-MM-DD"),
        endDate: values.period?.[1].format("YYYY-MM-DD"),
        templateId: values.templateId,
        workstreams: values.workstreams,
      });
      if (result.ok && result.data) {
        message.success(t("projects.created"));
        setOpen(false);
        form.resetFields();
        router.push(`/projects/${result.data.id}`);
      } else if (!result.ok) {
        message.error(result.message || t("common.error"));
      }
    });

  return (
    <>
      <PageHeader
        title={t("projects.title")}
        extra={
          canCreate && (
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>
              {t("projects.create")}
            </Button>
          )
        }
      />
      <Flex gap={12} wrap style={{ marginBottom: 16 }}>
        <Input.Search
          allowClear
          defaultValue={params.get("q") ?? ""}
          placeholder={t("projects.searchPlaceholder")}
          style={{ width: 280 }}
          onSearch={(q) => update({ q })}
        />
        <Select
          allowClear
          placeholder={t("projects.status")}
          value={params.get("status") ?? undefined}
          style={{ width: 160 }}
          options={(["ACTIVE", "ARCHIVED"] as const).map((s) => ({
            value: s,
            label: t(`projects.statusValues.${s}`),
          }))}
          onChange={(status) => update({ status })}
        />
      </Flex>
      <Table<Project>
        rowKey="id"
        dataSource={projects.items}
        scroll={{ x: 900 }}
        locale={{ emptyText: <Empty description={t("projects.emptyHint")} /> }}
        pagination={{
          current: projects.page + 1,
          pageSize: projects.size,
          total: projects.totalElements,
          showSizeChanger: false,
          onChange: (page) => update({ page: page - 1 }, false),
        }}
        columns={[
          {
            title: t("projects.name"),
            key: "name",
            render: (_, p) => (
              <Flex vertical>
                <Link href={`/projects/${p.id}`}>
                  <Typography.Text strong>{p.name}</Typography.Text>
                </Link>
                <Typography.Text type="secondary">{p.code}</Typography.Text>
              </Flex>
            ),
          },
          {
            title: t("projects.target"),
            key: "target",
            render: (_, p) => p.targetName ?? t("common.none"),
          },
          { title: t("projects.client"), key: "client", render: (_, p) => p.clientName ?? t("common.none") },
          { title: t("projects.stage"), key: "stage", render: (_, p) => t(`stages.${p.stage}`) },
          {
            title: t("projects.period"),
            key: "period",
            render: (_, p) => `${formatDate(p.startDate)} – ${formatDate(p.endDate)}`,
          },
          {
            title: t("projects.status"),
            key: "status",
            render: (_, p) => (
              <Tag color={p.status === "ACTIVE" ? "green" : "default"}>
                {t(`projects.statusValues.${p.status}`)}
              </Tag>
            ),
          },
          {
            title: t("projects.myRole"),
            key: "myRole",
            render: (_, p) => p.myRoles.map((role) => <Tag key={role}>{t(`projectRoles.${role}`)}</Tag>),
          },
        ]}
      />
      <Drawer
        title={t("projects.createTitle")}
        open={open}
        onClose={() => setOpen(false)}
        destroyOnHidden
        size="large"
        extra={
          <Button type="primary" loading={pending} onClick={() => form.submit()}>
            {t("common.create")}
          </Button>
        }
      >
        <Form<CreateForm> form={form} layout="vertical" onFinish={submit}>
          <Flex gap={12}>
            <Form.Item
              name="code"
              label={t("projects.code")}
              extra={t("projects.codeHelp")}
              rules={[{ required: true, max: 32, pattern: /^[A-Za-z0-9][A-Za-z0-9-]*$/ }]}
              style={{ flex: 1 }}
            >
              <Input />
            </Form.Item>
            <Form.Item
              name="name"
              label={t("projects.name")}
              rules={[{ required: true, max: 200 }]}
              style={{ flex: 2 }}
            >
              <Input />
            </Form.Item>
          </Flex>
          <Form.Item name="targetOrganizationId" label={t("projects.target")} rules={[{ required: true }]}>
            <Select
              showSearch={{ optionFilterProp: "label" }}
              options={targets.map((o) => ({ value: o.id, label: o.name }))}
            />
          </Form.Item>
          <Form.Item name="clientOrganizationId" label={t("projects.client")} rules={[{ required: true }]}>
            <Select
              showSearch={{ optionFilterProp: "label" }}
              options={clients.map((o) => ({ value: o.id, label: o.name }))}
            />
          </Form.Item>
          <Form.Item name="period" label={t("projects.period")}>
            <DatePicker.RangePicker style={{ width: "100%" }} />
          </Form.Item>
          <Form.Item name="templateId" label={t("projects.template")}>
            <Select
              allowClear
              placeholder={t("projects.templateNone")}
              options={templates.map((tpl) => ({ value: tpl.id, label: tpl.name }))}
            />
          </Form.Item>
          <Form.Item
            name="workstreams"
            label={t("projects.workstreams")}
            extra={t("projects.workstreamsHelp")}
            rules={[{ required: !templateId, type: "array", min: templateId ? 0 : 1 }]}
          >
            <Select
              mode="multiple"
              options={WORKSTREAMS.map((w) => ({ value: w, label: t(`workstreams.${w}`) }))}
            />
          </Form.Item>
        </Form>
      </Drawer>
    </>
  );
}
