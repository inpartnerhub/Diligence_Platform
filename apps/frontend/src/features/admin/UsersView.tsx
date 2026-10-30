"use client";

import { LockOutlined, PlusOutlined } from "@ant-design/icons";
import {
  App,
  Button,
  Drawer,
  Flex,
  Form,
  Input,
  Popconfirm,
  Radio,
  Select,
  Table,
  Tag,
  Typography,
} from "antd";
import { useFormatter, useTranslations } from "next-intl";
import { useState, useTransition } from "react";

import { PageHeader } from "@/components/PageHeader";
import { EXTERNAL_ROLES, MFA_ROLES, ROLES, type Role } from "@/lib/auth/roles";

import { changeUserRole, inviteUser, setUserEnabled } from "./actions";
import type { ActionResult, InviteUserInput, Organization, Page, User, UserStatus } from "./types";
import { useUrlQuery } from "./useUrlQuery";

const STATUS_COLORS: Record<UserStatus, string> = { INVITED: "blue", ACTIVE: "green", DISABLED: "default" };

export function UsersView({
  users,
  organizations,
}: Readonly<{ users: Page<User>; organizations: Organization[] }>) {
  const t = useTranslations();
  const format = useFormatter();
  const { message } = App.useApp();
  const { params, update } = useUrlQuery();
  const [inviteOpen, setInviteOpen] = useState(false);
  const [pending, startTransition] = useTransition();
  const [form] = Form.useForm<InviteUserInput>();
  const orgNames = new Map(organizations.map((o) => [o.id, o.name]));
  const roleOptions = ROLES.map((role) => ({ value: role, label: t(`roles.${role}`) }));

  const handle = (result: ActionResult, success: string) => {
    if (result.ok) {
      message.success(success);
    } else {
      message.error(result.message || t("common.error"));
    }
    return result.ok;
  };

  const submitInvite = (values: InviteUserInput) =>
    startTransition(async () => {
      const extra =
        (MFA_ROLES.includes(values.role) ? t("users.inviteExtraMfa") : "") +
        (EXTERNAL_ROLES.includes(values.role) ? t("users.inviteExtraNda") : "");
      if (handle(await inviteUser(values), t("users.invited", { extra }))) {
        form.resetFields();
        setInviteOpen(false);
      }
    });

  return (
    <>
      <PageHeader
        title={t("users.title")}
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setInviteOpen(true)}>
            {t("users.invite")}
          </Button>
        }
      />
      <Flex gap={12} wrap style={{ marginBottom: 16 }}>
        <Input.Search
          allowClear
          defaultValue={params.get("q") ?? ""}
          placeholder={t("users.searchPlaceholder")}
          style={{ width: 280 }}
          onSearch={(q) => update({ q })}
        />
        <Select
          allowClear
          placeholder={t("common.role")}
          value={params.get("role") ?? undefined}
          options={roleOptions}
          style={{ width: 220 }}
          onChange={(role) => update({ role })}
        />
        <Select
          allowClear
          placeholder={t("common.status")}
          value={params.get("status") ?? undefined}
          options={(["INVITED", "ACTIVE", "DISABLED"] as const).map((s) => ({
            value: s,
            label: t(`users.status.${s}`),
          }))}
          style={{ width: 160 }}
          onChange={(status) => update({ status })}
        />
      </Flex>
      <Table<User>
        rowKey="id"
        dataSource={users.items}
        loading={pending}
        scroll={{ x: 900 }}
        pagination={{
          current: users.page + 1,
          pageSize: users.size,
          total: users.totalElements,
          showSizeChanger: false,
          onChange: (page) => update({ page: page - 1 }, false),
        }}
        columns={[
          {
            title: t("common.name"),
            key: "name",
            render: (_, u) => (
              <Flex vertical>
                <Typography.Text strong>{u.displayName}</Typography.Text>
                <Typography.Text type="secondary">{u.email}</Typography.Text>
              </Flex>
            ),
          },
          {
            title: t("common.organization"),
            key: "org",
            render: (_, u) => (u.organizationId ? orgNames.get(u.organizationId) : null) ?? t("common.none"),
          },
          {
            title: t("common.role"),
            key: "role",
            render: (_, u) => (
              <Select<Role>
                size="small"
                value={u.role}
                options={roleOptions}
                style={{ width: 200 }}
                onChange={(role) =>
                  startTransition(async () => {
                    handle(await changeUserRole(u.id, role), t("common.saved"));
                  })
                }
              />
            ),
          },
          {
            title: t("common.status"),
            key: "status",
            render: (_, u) => <Tag color={STATUS_COLORS[u.status]}>{t(`users.status.${u.status}`)}</Tag>,
          },
          {
            title: t("users.mfa"),
            key: "mfa",
            render: (_, u) => (u.mfaEnabled ? <LockOutlined aria-label={t("users.mfa")} /> : null),
          },
          {
            title: t("users.lastLogin"),
            key: "lastLogin",
            render: (_, u) =>
              u.lastLoginAt
                ? format.dateTime(new Date(u.lastLoginAt), { dateStyle: "medium", timeStyle: "short" })
                : t("common.none"),
          },
          {
            title: t("common.actions"),
            key: "actions",
            render: (_, u) =>
              u.status === "DISABLED" ? (
                <Button
                  size="small"
                  onClick={() =>
                    startTransition(async () => {
                      handle(await setUserEnabled(u.id, true), t("common.saved"));
                    })
                  }
                >
                  {t("users.enable")}
                </Button>
              ) : (
                <Popconfirm
                  title={t("users.disableConfirm")}
                  okText={t("common.yes")}
                  cancelText={t("common.no")}
                  onConfirm={() =>
                    startTransition(async () => {
                      handle(await setUserEnabled(u.id, false), t("common.saved"));
                    })
                  }
                >
                  <Button size="small" danger>
                    {t("users.disable")}
                  </Button>
                </Popconfirm>
              ),
          },
        ]}
      />
      <Drawer
        title={t("users.inviteTitle")}
        open={inviteOpen}
        onClose={() => setInviteOpen(false)}
        destroyOnHidden
        size="large"
        extra={
          <Button type="primary" loading={pending} onClick={() => form.submit()}>
            {t("users.invite")}
          </Button>
        }
      >
        <Form<InviteUserInput>
          form={form}
          layout="vertical"
          initialValues={{ locale: "id" }}
          onFinish={submitInvite}
        >
          <Form.Item name="email" label={t("common.email")} rules={[{ required: true, type: "email" }]}>
            <Input autoComplete="off" />
          </Form.Item>
          <Flex gap={12}>
            <Form.Item
              name="firstName"
              label={t("users.firstName")}
              rules={[{ required: true }]}
              style={{ flex: 1 }}
            >
              <Input />
            </Form.Item>
            <Form.Item
              name="lastName"
              label={t("users.lastName")}
              rules={[{ required: true }]}
              style={{ flex: 1 }}
            >
              <Input />
            </Form.Item>
          </Flex>
          <Form.Item name="organizationId" label={t("common.organization")}>
            <Select
              allowClear
              showSearch={{ optionFilterProp: "label" }}
              options={organizations
                .filter((o) => o.status === "ACTIVE")
                .map((o) => ({ value: o.id, label: o.name }))}
            />
          </Form.Item>
          <Form.Item name="role" label={t("common.role")} rules={[{ required: true }]}>
            <Select options={roleOptions} />
          </Form.Item>
          <Form.Item name="locale" label={t("users.locale")}>
            <Radio.Group
              options={[
                { value: "id", label: "Bahasa Indonesia" },
                { value: "en", label: "English" },
              ]}
            />
          </Form.Item>
        </Form>
      </Drawer>
    </>
  );
}
