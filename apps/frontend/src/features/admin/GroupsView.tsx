"use client";

import { PlusOutlined } from "@ant-design/icons";
import { App, Button, Form, Input, Modal, Select, Table } from "antd";
import { useTranslations } from "next-intl";
import { useState, useTransition } from "react";

import { PageHeader } from "@/components/PageHeader";

import { createGroup, replaceGroupMembers } from "./actions";
import type { ActionResult, Group, User } from "./types";

export function GroupsView({ groups, users }: Readonly<{ groups: Group[]; users: User[] }>) {
  const t = useTranslations();
  const { message } = App.useApp();
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<Group | null>(null);
  const [members, setMembers] = useState<string[]>([]);
  const [pending, startTransition] = useTransition();
  const [form] = Form.useForm<{ name: string; description?: string }>();
  const userOptions = users.map((u) => ({ value: u.id, label: `${u.displayName} <${u.email}>` }));

  const handle = (result: ActionResult) => {
    if (result.ok) {
      message.success(t("common.saved"));
    } else {
      message.error(result.message || t("common.error"));
    }
    return result.ok;
  };

  return (
    <>
      <PageHeader
        title={t("groups.title")}
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>
            {t("groups.create")}
          </Button>
        }
      />
      <Table<Group>
        rowKey="id"
        dataSource={groups}
        loading={pending}
        pagination={false}
        columns={[
          { title: t("common.name"), dataIndex: "name" },
          { title: t("groups.description"), dataIndex: "description" },
          { title: t("groups.members"), key: "members", render: (_, g) => g.memberIds.length },
          {
            title: t("common.actions"),
            key: "actions",
            render: (_, g) => (
              <Button
                size="small"
                onClick={() => {
                  setMembers(g.memberIds);
                  setEditing(g);
                }}
              >
                {t("groups.editMembers")}
              </Button>
            ),
          },
        ]}
      />
      <Modal
        title={t("groups.create")}
        open={creating}
        okText={t("common.create")}
        cancelText={t("common.cancel")}
        confirmLoading={pending}
        onOk={() => form.submit()}
        onCancel={() => setCreating(false)}
        destroyOnHidden
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={(values) =>
            startTransition(async () => {
              if (handle(await createGroup(values.name, values.description))) {
                setCreating(false);
              }
            })
          }
        >
          <Form.Item name="name" label={t("common.name")} rules={[{ required: true, max: 200 }]}>
            <Input />
          </Form.Item>
          <Form.Item name="description" label={t("groups.description")} rules={[{ max: 1000 }]}>
            <Input.TextArea rows={3} />
          </Form.Item>
        </Form>
      </Modal>
      <Modal
        title={editing ? `${t("groups.editMembers")}: ${editing.name}` : ""}
        open={editing !== null}
        okText={t("common.save")}
        cancelText={t("common.cancel")}
        confirmLoading={pending}
        onCancel={() => setEditing(null)}
        onOk={() =>
          editing &&
          startTransition(async () => {
            if (handle(await replaceGroupMembers(editing.id, members))) {
              setEditing(null);
            }
          })
        }
      >
        <Select
          mode="multiple"
          style={{ width: "100%" }}
          value={members}
          onChange={setMembers}
          options={userOptions}
          showSearch={{ optionFilterProp: "label" }}
        />
      </Modal>
    </>
  );
}
