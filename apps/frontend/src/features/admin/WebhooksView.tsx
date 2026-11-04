"use client";

import {
  ApiOutlined,
  DeleteOutlined,
  EditOutlined,
  HistoryOutlined,
  KeyOutlined,
  PlusOutlined,
  SendOutlined,
} from "@ant-design/icons";
import {
  App,
  Badge,
  Button,
  Card,
  Drawer,
  Empty,
  Flex,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Tooltip,
  Typography,
} from "antd";
import type { TableColumnsType } from "antd";
import { useRouter } from "next/navigation";
import { useFormatter, useTranslations } from "next-intl";
import { useState, useTransition } from "react";

import {
  createWebhook,
  deleteWebhook,
  loadWebhookDeliveries,
  rotateWebhookSigningKey,
  testWebhook,
  updateWebhook,
} from "./actions";
import type {
  Organization,
  WebhookDeliveryLog,
  WebhookSubscription,
  WebhookSubscriptionInput,
} from "./types";

const EVENT_OPTIONS = [
  { value: "*", label: "* (All platform events)" },
  { value: "finding.*", label: "finding.* (All finding events)" },
  { value: "finding.status-changed", label: "finding.status-changed" },
  { value: "report.*", label: "report.* (All report events)" },
  { value: "report.issued", label: "report.issued" },
  { value: "request.*", label: "request.* (All request item events)" },
  { value: "request.submitted", label: "request.submitted" },
  { value: "project.*", label: "project.* (All project events)" },
  { value: "qna.*", label: "qna.* (All Q&A events)" },
  { value: "dataroom.*", label: "dataroom.* (All data room events)" },
];

/** The signing key is returned once (create, rotate); show it so the administrator can copy it. */
function useRevealSigningKey() {
  const t = useTranslations("webhooks");
  const { modal } = App.useApp();
  return (key: string | null) => {
    if (!key) return;
    modal.info({
      title: t("signingKeyRevealTitle"),
      content: (
        <Flex vertical gap={8}>
          <Typography.Text>{t("signingKeyRevealBody")}</Typography.Text>
          <Typography.Text code copyable={{ text: key }}>
            {key}
          </Typography.Text>
        </Flex>
      ),
    });
  };
}

export function WebhooksView({
  subscriptions,
  organizations,
}: Readonly<{
  subscriptions: WebhookSubscription[];
  organizations: Organization[];
}>) {
  const t = useTranslations("webhooks");
  const format = useFormatter();
  const router = useRouter();
  const { message } = App.useApp();
  const revealSigningKey = useRevealSigningKey();

  const [editing, setEditing] = useState<WebhookSubscription | "new" | null>(null);
  const [testingId, setTestingId] = useState<string | null>(null);
  const [logsTarget, setLogsTarget] = useState<WebhookSubscription | null>(null);
  const [logs, setLogs] = useState<WebhookDeliveryLog[]>([]);
  const [logsLoading, setLogsLoading] = useState(false);
  const [, startTransition] = useTransition();

  const date = (value: string) =>
    format.dateTime(new Date(value), { dateStyle: "medium", timeStyle: "short" });

  const handleOpenLogs = async (sub: WebhookSubscription) => {
    setLogsTarget(sub);
    setLogsLoading(true);
    const res = await loadWebhookDeliveries(sub.id);
    setLogsLoading(false);
    if (res.ok) {
      setLogs(res.data ?? []);
    } else {
      setLogs([]);
      message.error(res.message);
    }
  };

  const handleTestPing = (sub: WebhookSubscription) => {
    setTestingId(sub.id);
    startTransition(async () => {
      const res = await testWebhook(sub.id);
      setTestingId(null);
      if (res.ok && res.data) {
        if (res.data.success) {
          message.success(
            t("testSuccess", { code: res.data.statusCode ?? 200, duration: res.data.durationMs }),
          );
        } else {
          message.warning(
            t("testFailed", {
              error: res.data.errorMessage ?? `HTTP ${res.data.statusCode ?? "unknown"}`,
            }),
          );
        }
      } else if (!res.ok) {
        message.error(res.message);
      } else {
        message.error(t("error"));
      }
    });
  };

  const handleRotateKey = (sub: WebhookSubscription) => {
    startTransition(async () => {
      const res = await rotateWebhookSigningKey(sub.id);
      if (res.ok && res.data) {
        revealSigningKey(res.data.signingKey);
        router.refresh();
      } else if (!res.ok) {
        message.error(res.message || t("error"));
      }
    });
  };

  const handleToggleActive = (sub: WebhookSubscription, active: boolean) => {
    startTransition(async () => {
      const res = await updateWebhook(sub.id, { active });
      if (res.ok) {
        message.success(t(active ? "enabledSuccess" : "disabledSuccess"));
        router.refresh();
      } else {
        message.error(res.message);
      }
    });
  };

  const columns: TableColumnsType<WebhookSubscription> = [
    {
      title: t("targetUrl"),
      dataIndex: "targetUrl",
      ellipsis: true,
      render: (url: string) => (
        <Space>
          <Typography.Text copyable={{ text: url }} style={{ fontFamily: "monospace", fontSize: 13 }}>
            {url}
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: t("description"),
      dataIndex: "description",
      width: 200,
      render: (d: string | null) => d ?? "—",
    },
    {
      title: t("subscribedEvents"),
      dataIndex: "subscribedEvents",
      width: 220,
      render: (events: string) => (
        <Space size={[0, 4]} wrap>
          {events.split(",").map((ev) => (
            <Tag key={ev.trim()} color={ev.trim() === "*" ? "purple" : "blue"}>
              {ev.trim()}
            </Tag>
          ))}
        </Space>
      ),
    },
    {
      title: t("signingKey"),
      dataIndex: "signingKeyHint",
      width: 170,
      render: (hint: string) => <Typography.Text code>{hint}</Typography.Text>,
    },
    {
      title: t("status"),
      dataIndex: "active",
      width: 110,
      render: (active: boolean, sub) => (
        <Switch
          checked={active}
          checkedChildren={t("active")}
          unCheckedChildren={t("inactive")}
          onChange={(val) => handleToggleActive(sub, val)}
        />
      ),
    },
    {
      title: t("created"),
      dataIndex: "createdAt",
      width: 170,
      render: (d: string) => date(d),
    },
    {
      title: t("actions"),
      key: "actions",
      width: 240,
      render: (_, sub) => (
        <Space size="small">
          <Tooltip title={t("testPing")}>
            <Button
              size="small"
              icon={<SendOutlined />}
              loading={testingId === sub.id}
              onClick={() => handleTestPing(sub)}
            >
              {t("test")}
            </Button>
          </Tooltip>
          <Tooltip title={t("viewLogs")}>
            <Button size="small" icon={<HistoryOutlined />} onClick={() => handleOpenLogs(sub)}>
              {t("logs")}
            </Button>
          </Tooltip>
          <Popconfirm title={t("rotateKeyConfirm")} onConfirm={() => handleRotateKey(sub)}>
            <Tooltip title={t("rotateKey")}>
              <Button size="small" icon={<KeyOutlined />} aria-label={t("rotateKey")} />
            </Tooltip>
          </Popconfirm>
          <Button size="small" icon={<EditOutlined />} onClick={() => setEditing(sub)} />
          <Popconfirm
            title={t("deleteConfirm")}
            onConfirm={() =>
              startTransition(async () => {
                const res = await deleteWebhook(sub.id);
                if (res.ok) {
                  message.success(t("deletedSuccess"));
                  router.refresh();
                } else {
                  message.error(res.message || t("error"));
                }
              })
            }
          >
            <Button size="small" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  const formatLogStatus = (log: WebhookDeliveryLog) => {
    if (log.statusCode) {
      return `HTTP ${log.statusCode}`;
    }
    return log.success ? "SUCCESS" : "FAILED";
  };

  const renderDrawerContent = () => {
    if (logsLoading) {
      return (
        <Flex justify="center" style={{ padding: 40 }}>
          <Typography.Text>{t("loadingLogs")}</Typography.Text>
        </Flex>
      );
    }
    if (logs.length === 0) {
      return <Empty description={t("noLogsFound")} style={{ marginTop: 40 }} />;
    }
    return (
      <Flex vertical gap={12}>
        {logs.map((log) => (
          <Card key={log.id} size="small" styles={{ body: { padding: 12 } }}>
            <Flex justify="space-between" align="center" style={{ marginBottom: 6 }}>
              <Space>
                <Badge status={log.success ? "success" : "error"} />
                <Tag color={log.success ? "green" : "red"}>{formatLogStatus(log)}</Tag>
                <Tag>{log.eventType}</Tag>
              </Space>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {date(log.deliveredAt)} · {log.durationMs ?? 0}ms · {log.attemptCount} attempt(s)
              </Typography.Text>
            </Flex>

            {log.errorMessage && (
              <Typography.Paragraph type="danger" style={{ margin: "4px 0", fontSize: 12 }}>
                {log.errorMessage}
              </Typography.Paragraph>
            )}

            <details style={{ marginTop: 6, fontSize: 12 }}>
              <summary style={{ cursor: "pointer", color: "#1677ff" }}>{t("viewPayload")}</summary>
              <pre
                style={{
                  background: "#f5f5f5",
                  padding: 8,
                  borderRadius: 4,
                  marginTop: 4,
                  maxHeight: 180,
                  overflow: "auto",
                  fontSize: 11,
                }}
              >
                {log.payload}
              </pre>
            </details>
          </Card>
        ))}
      </Flex>
    );
  };

  return (
    <>
      <Flex justify="space-between" align="center" style={{ marginBottom: 16 }}>
        <div>
          <Typography.Title level={4} style={{ margin: 0 }}>
            <ApiOutlined /> {t("title")}
          </Typography.Title>
          <Typography.Text type="secondary">{t("subtitle")}</Typography.Text>
        </div>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setEditing("new")}>
          {t("newWebhook")}
        </Button>
      </Flex>

      <Card styles={{ body: { padding: 0 } }}>
        <Table<WebhookSubscription>
          rowKey="id"
          columns={columns}
          dataSource={subscriptions}
          pagination={false}
          locale={{ emptyText: t("empty") }}
        />
      </Card>

      {/* Create / Edit Modal */}
      {editing && (
        <WebhookModal
          subscription={editing === "new" ? null : editing}
          organizations={organizations}
          onClose={() => setEditing(null)}
        />
      )}

      {/* Delivery Logs Drawer */}
      <Drawer
        title={
          <Space>
            <HistoryOutlined />
            <span>{t("deliveryLogsTitle")}</span>
          </Space>
        }
        open={Boolean(logsTarget)}
        onClose={() => setLogsTarget(null)}
        size="large"
      >
        {renderDrawerContent()}
      </Drawer>
    </>
  );
}

function WebhookModal({
  subscription,
  organizations,
  onClose,
}: Readonly<{
  subscription: WebhookSubscription | null;
  organizations: Organization[];
  onClose: () => void;
}>) {
  const t = useTranslations("webhooks");
  const { message } = App.useApp();
  const router = useRouter();
  const revealSigningKey = useRevealSigningKey();
  const [form] = Form.useForm();
  const [saving, setSaving] = useState(false);

  const initialValues = subscription
    ? {
        targetUrl: subscription.targetUrl,
        description: subscription.description,
        subscribedEvents: subscription.subscribedEvents.split(",").map((s) => s.trim()),
        active: subscription.active,
        organizationId: subscription.organizationId,
      }
    : {
        targetUrl: "https://",
        subscribedEvents: ["*"],
        active: true,
      };

  const handleFinish = async (values: {
    targetUrl: string;
    description?: string;
    signingKey?: string;
    subscribedEvents: string[];
    active: boolean;
    organizationId?: string;
  }) => {
    setSaving(true);
    const input: WebhookSubscriptionInput = {
      targetUrl: values.targetUrl.trim(),
      description: values.description?.trim() || null,
      signingKey: values.signingKey?.trim() || null,
      subscribedEvents: values.subscribedEvents.join(","),
      active: values.active,
      organizationId: values.organizationId || null,
    };

    const res = subscription ? await updateWebhook(subscription.id, input) : await createWebhook(input);

    setSaving(false);
    if (res.ok) {
      message.success(t(subscription ? "updatedSuccess" : "createdSuccess"));
      if (!subscription) revealSigningKey(res.data?.signingKey ?? null);
      onClose();
      router.refresh();
    } else {
      message.error(res.message || t("error"));
    }
  };

  return (
    <Modal
      title={subscription ? t("editWebhook") : t("newWebhook")}
      open
      onCancel={onClose}
      onOk={() => form.submit()}
      confirmLoading={saving}
      okText={t("save")}
    >
      <Form form={form} layout="vertical" initialValues={initialValues} onFinish={handleFinish}>
        <Form.Item
          name="targetUrl"
          label={t("targetUrl")}
          rules={[
            { required: true, message: t("targetUrlRequired") },
            {
              type: "url",
              message: t("targetUrlInvalid"),
            },
          ]}
          extra={t("targetUrlHelp")}
        >
          <Input placeholder="https://api.yourdomain.com/webhooks" />
        </Form.Item>

        <Form.Item name="description" label={t("description")}>
          <Input placeholder="e.g. Slack incident notifier, ERP billing listener" />
        </Form.Item>

        <Form.Item
          name="subscribedEvents"
          label={t("subscribedEvents")}
          rules={[{ required: true, message: t("eventsRequired") }]}
          extra={t("subscribedEventsHelp")}
        >
          <Select
            mode="tags"
            style={{ width: "100%" }}
            placeholder={t("selectEvents")}
            options={EVENT_OPTIONS}
          />
        </Form.Item>

        <Form.Item
          name="signingKey"
          label={t("signingKey")}
          extra={subscription ? t("signingKeyKeepHelp") : t("signingKeyHelp")}
        >
          <Input.Password placeholder={t("signingKeyPlaceholder")} />
        </Form.Item>

        {organizations.length > 0 && (
          <Form.Item name="organizationId" label={t("organization")}>
            <Select
              allowClear
              placeholder={t("allOrganizations")}
              options={organizations.map((org) => ({
                value: org.id,
                label: org.name,
              }))}
            />
          </Form.Item>
        )}

        <Form.Item name="active" label={t("active")} valuePropName="checked">
          <Switch />
        </Form.Item>
      </Form>
    </Modal>
  );
}
