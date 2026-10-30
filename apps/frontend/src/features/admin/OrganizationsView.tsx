"use client";

import { PlusOutlined } from "@ant-design/icons";
import { App, Button, Form, Input, Modal, Select, Table, Tag } from "antd";
import { useFormatter, useTranslations } from "next-intl";
import { useState, useTransition } from "react";

import { PageHeader } from "@/components/PageHeader";
import { useUrlQuery } from "@/lib/navigation/useUrlQuery";

import { createOrganization, setOrganizationStatus } from "./actions";
import type { Organization, OrganizationType, Page } from "./types";

const TYPES: OrganizationType[] = ["CONSULTANT", "CLIENT", "TARGET"];

export function OrganizationsView({ organizations }: Readonly<{ organizations: Page<Organization> }>) {
  const t = useTranslations();
  const format = useFormatter();
  const { message } = App.useApp();
  const { update } = useUrlQuery();
  const [open, setOpen] = useState(false);
  const [pending, startTransition] = useTransition();
  const [form] = Form.useForm<{ name: string; type: OrganizationType }>();

  const notify = (ok: boolean, error: string) =>
    ok ? message.success(t("common.saved")) : message.error(error || t("common.error"));

  return (
    <>
      <PageHeader
        title={t("organizations.title")}
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>
            {t("organizations.create")}
          </Button>
        }
      />
      <Table<Organization>
        rowKey="id"
        dataSource={organizations.items}
        loading={pending}
        pagination={{
          current: organizations.page + 1,
          pageSize: organizations.size,
          total: organizations.totalElements,
          showSizeChanger: false,
          onChange: (page) => update({ page: page - 1 }, false),
        }}
        columns={[
          { title: t("common.name"), dataIndex: "name" },
          {
            title: t("organizations.type"),
            key: "type",
            render: (_, o) => t(`organizations.types.${o.type}`),
          },
          {
            title: t("common.status"),
            key: "status",
            render: (_, o) => (
              <Tag color={o.status === "ACTIVE" ? "green" : "default"}>
                {t(`organizations.status.${o.status}`)}
              </Tag>
            ),
          },
          {
            title: t("organizations.createdAt"),
            key: "createdAt",
            render: (_, o) => format.dateTime(new Date(o.createdAt), { dateStyle: "medium" }),
          },
          {
            title: t("common.actions"),
            key: "actions",
            render: (_, o) => (
              <Button
                size="small"
                onClick={() =>
                  startTransition(async () => {
                    const result = await setOrganizationStatus(
                      o.id,
                      o.status === "ACTIVE" ? "INACTIVE" : "ACTIVE",
                    );
                    notify(result.ok, result.ok ? "" : result.message);
                  })
                }
              >
                {o.status === "ACTIVE" ? t("organizations.deactivate") : t("organizations.activate")}
              </Button>
            ),
          },
        ]}
      />
      <Modal
        title={t("organizations.create")}
        open={open}
        okText={t("common.create")}
        cancelText={t("common.cancel")}
        confirmLoading={pending}
        onOk={() => form.submit()}
        onCancel={() => setOpen(false)}
        destroyOnHidden
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={(values) =>
            startTransition(async () => {
              const result = await createOrganization(values.name, values.type);
              notify(result.ok, result.ok ? "" : result.message);
              if (result.ok) {
                setOpen(false);
              }
            })
          }
        >
          <Form.Item name="name" label={t("common.name")} rules={[{ required: true, max: 200 }]}>
            <Input />
          </Form.Item>
          <Form.Item name="type" label={t("organizations.type")} rules={[{ required: true }]}>
            <Select
              options={TYPES.map((type) => ({ value: type, label: t(`organizations.types.${type}`) }))}
            />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
