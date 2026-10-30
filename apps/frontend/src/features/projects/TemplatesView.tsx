"use client";

import { CopyOutlined, EditOutlined, PlusOutlined } from "@ant-design/icons";
import { App, Button, Form, Input, Modal, Select, Space, Table, Tag, Typography } from "antd";
import { useTranslations } from "next-intl";
import { useState, useTransition } from "react";

import { PageHeader } from "@/components/PageHeader";

import { duplicateTemplate, saveTemplate } from "./actions";
import {
  findingCategoriesToText,
  parseFindingCategories,
  parseFolders,
  parseRequestItems,
  requestItemsToText,
} from "./templateText";
import { WORKSTREAMS, type ActionResult, type ProjectTemplate, type WorkstreamType } from "./types";

interface TemplateForm {
  name: string;
  description?: string;
  workstreams: WorkstreamType[];
  requestItems?: string;
  findingCategories?: string;
  folders?: string;
}

export function TemplatesView({ templates }: Readonly<{ templates: ProjectTemplate[] }>) {
  const t = useTranslations();
  const { message } = App.useApp();
  const [editing, setEditing] = useState<ProjectTemplate | "new" | null>(null);
  const [copying, setCopying] = useState<ProjectTemplate | null>(null);
  const [copyName, setCopyName] = useState("");
  const [pending, startTransition] = useTransition();
  const [form] = Form.useForm<TemplateForm>();

  const handle = (result: ActionResult) => {
    if (result.ok) {
      message.success(t("common.saved"));
    } else {
      message.error(result.message || t("common.error"));
    }
    return result.ok;
  };

  const openEditor = (template: ProjectTemplate | "new") => {
    setEditing(template);
    form.setFieldsValue(
      template === "new"
        ? { name: "", description: "", workstreams: [], requestItems: "", findingCategories: "", folders: "" }
        : {
            name: template.name,
            description: template.description ?? "",
            workstreams: template.definition.workstreams,
            requestItems: requestItemsToText(template.definition.requestItems),
            findingCategories: findingCategoriesToText(template.definition.findingCategories),
            folders: template.definition.folders.join("\n"),
          },
    );
  };

  const submit = (values: TemplateForm) => {
    const requestItems = parseRequestItems(values.requestItems);
    const findingCategories = parseFindingCategories(values.findingCategories);
    if (!requestItems.ok) {
      form.setFields([
        { name: "requestItems", errors: [t("templates.invalidLine", { line: requestItems.line })] },
      ]);
      return;
    }
    if (!findingCategories.ok) {
      form.setFields([
        { name: "findingCategories", errors: [t("templates.invalidLine", { line: findingCategories.line })] },
      ]);
      return;
    }
    startTransition(async () => {
      const id = editing === "new" || editing === null ? null : editing.id;
      const ok = handle(
        await saveTemplate(id, {
          name: values.name,
          description: values.description,
          definition: {
            workstreams: values.workstreams,
            requestItems: requestItems.value,
            findingCategories: findingCategories.value,
            folders: parseFolders(values.folders),
          },
        }),
      );
      if (ok) {
        setEditing(null);
      }
    });
  };

  return (
    <>
      <PageHeader
        title={t("templates.title")}
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={() => openEditor("new")}>
            {t("templates.create")}
          </Button>
        }
      />
      <Table<ProjectTemplate>
        rowKey="id"
        dataSource={templates}
        loading={pending}
        pagination={false}
        scroll={{ x: 800 }}
        columns={[
          {
            title: t("common.name"),
            key: "name",
            render: (_, tpl) => (
              <Space orientation="vertical" size={0}>
                <Typography.Text strong>{tpl.name}</Typography.Text>
                {tpl.description && <Typography.Text type="secondary">{tpl.description}</Typography.Text>}
              </Space>
            ),
          },
          {
            title: t("projects.workstreams"),
            key: "workstreams",
            render: (_, tpl) => (
              <Space wrap>
                {tpl.definition.workstreams.map((w) => (
                  <Tag key={w}>{t(`workstreams.${w}`)}</Tag>
                ))}
              </Space>
            ),
          },
          {
            title: t("templates.description"),
            key: "counts",
            render: (_, tpl) =>
              t("templates.counts", {
                requestItems: tpl.definition.requestItems.length,
                findingCategories: tpl.definition.findingCategories.length,
                folders: tpl.definition.folders.length,
              }),
          },
          {
            title: t("common.actions"),
            key: "actions",
            render: (_, tpl) => (
              <Space>
                <Button size="small" icon={<EditOutlined />} onClick={() => openEditor(tpl)}>
                  {t("templates.edit")}
                </Button>
                <Button
                  size="small"
                  icon={<CopyOutlined />}
                  onClick={() => {
                    setCopyName(`${tpl.name} (copy)`);
                    setCopying(tpl);
                  }}
                >
                  {t("templates.duplicate")}
                </Button>
              </Space>
            ),
          },
        ]}
      />
      <Modal
        title={editing === "new" ? t("templates.create") : t("templates.edit")}
        open={editing !== null}
        width={760}
        okText={t("common.save")}
        cancelText={t("common.cancel")}
        confirmLoading={pending}
        onOk={() => form.submit()}
        onCancel={() => setEditing(null)}
        forceRender
      >
        <Form<TemplateForm> form={form} layout="vertical" onFinish={submit}>
          <Form.Item name="name" label={t("common.name")} rules={[{ required: true, max: 200 }]}>
            <Input />
          </Form.Item>
          <Form.Item name="description" label={t("templates.description")} rules={[{ max: 2000 }]}>
            <Input.TextArea rows={2} />
          </Form.Item>
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
            name="requestItems"
            label={t("templates.requestItems")}
            extra={t("templates.requestItemsHelp")}
          >
            <Input.TextArea rows={6} style={{ fontFamily: "monospace" }} />
          </Form.Item>
          <Form.Item
            name="findingCategories"
            label={t("templates.findingCategories")}
            extra={t("templates.findingCategoriesHelp")}
          >
            <Input.TextArea rows={4} style={{ fontFamily: "monospace" }} />
          </Form.Item>
          <Form.Item name="folders" label={t("templates.folders")} extra={t("templates.foldersHelp")}>
            <Input.TextArea rows={4} style={{ fontFamily: "monospace" }} />
          </Form.Item>
        </Form>
      </Modal>
      <Modal
        title={t("templates.duplicate")}
        open={copying !== null}
        okText={t("common.create")}
        cancelText={t("common.cancel")}
        confirmLoading={pending}
        okButtonProps={{ disabled: !copyName.trim() }}
        onCancel={() => setCopying(null)}
        onOk={() =>
          copying &&
          startTransition(async () => {
            if (handle(await duplicateTemplate(copying.id, copyName.trim()))) {
              setCopying(null);
            }
          })
        }
      >
        <Typography.Text>{t("templates.duplicateName")}</Typography.Text>
        <Input value={copyName} onChange={(e) => setCopyName(e.target.value)} style={{ marginTop: 8 }} />
      </Modal>
    </>
  );
}
