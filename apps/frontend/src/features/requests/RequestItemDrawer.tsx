"use client";

import {
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  LinkOutlined,
  LockOutlined,
  QuestionCircleOutlined,
  UploadOutlined,
} from "@ant-design/icons";
import {
  Alert,
  App,
  Button,
  Checkbox,
  Descriptions,
  Divider,
  Drawer,
  Empty,
  Flex,
  Input,
  List,
  Modal,
  Popconfirm,
  Select,
  Space,
  Spin,
  Tag,
  Timeline,
  TreeSelect,
  Typography,
  Upload,
} from "antd";
import type { UploadProps } from "antd";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useFormatter, useTranslations } from "next-intl";
import { useCallback, useEffect, useMemo, useState, useTransition } from "react";

import { DocumentViewer, type ViewerTarget } from "@/features/dataroom/DocumentViewer";
import {
  buildTree,
  formatBytes,
  type DataRoomDocument,
  type Folder,
  type UploadResponse,
} from "@/features/dataroom/types";
import type { Project } from "@/features/projects/types";

import {
  addComment,
  changeStatus,
  deleteItem,
  linkDocument,
  loadFolderDocuments,
  loadFolders,
  loadItem,
  unlinkDocument,
} from "./actions";
import {
  PRIORITY_COLORS,
  STATUS_COLORS,
  nextStatuses,
  type RequestItem,
  type RequestItemDetail,
  type RequestPermissions,
  type RequestStatus,
} from "./types";

export function RequestItemDrawer({
  project,
  itemId,
  permissions,
  onEdit,
  onClose,
}: Readonly<{
  project: Project;
  itemId: string | null;
  permissions: RequestPermissions;
  onEdit: (item: RequestItem) => void;
  onClose: () => void;
}>) {
  const t = useTranslations("requests");
  const tw = useTranslations("workstreams");
  const format = useFormatter();
  const router = useRouter();
  const { message } = App.useApp();
  // The detail is kept with the id it belongs to, so switching items shows the spinner until the new one arrives.
  const [loaded, setLoaded] = useState<{
    id: string;
    detail: RequestItemDetail | null;
    error?: string;
  } | null>(null);
  const [version, setVersion] = useState(0);
  const [viewing, setViewing] = useState<ViewerTarget | null>(null);
  const [rejecting, setRejecting] = useState(false);
  const [pending, startTransition] = useTransition();

  useEffect(() => {
    if (!itemId) return undefined;
    let active = true;
    void loadItem(project.id, itemId).then((result) => {
      if (active) {
        setLoaded({
          id: itemId,
          detail: result.ok ? (result.data ?? null) : null,
          error: result.ok ? undefined : result.message,
        });
      }
    });
    return () => {
      active = false;
    };
  }, [project.id, itemId, version]);

  const reload = useCallback(() => {
    setVersion((v) => v + 1);
    router.refresh();
  }, [router]);

  const detail = loaded?.id === itemId ? loaded.detail : null;
  const item = detail?.item;

  const act = (call: () => Promise<{ ok: boolean; message?: string }>, success?: string) =>
    startTransition(async () => {
      const result = await call();
      if (!result.ok) {
        message.error(("message" in result && result.message) || t("error"));
        return;
      }
      if (success) message.success(success);
      reload();
    });

  const moveTo = (status: RequestStatus) => {
    if (!item) return;
    if (status === "REJECTED") {
      setRejecting(true);
      return;
    }
    act(
      () => changeStatus(project.id, item.id, status),
      t("statusChanged", { status: t(`statusValues.${status}`) }),
    );
  };

  const statuses = item ? nextStatuses(item.status, permissions, (detail?.documents.length ?? 0) > 0) : [];
  const canDeliver =
    item !== undefined &&
    (permissions.deliver || permissions.edit) &&
    item.status !== "NOT_REQUESTED" &&
    item.status !== "CLOSED";

  return (
    <Drawer
      open={itemId !== null}
      onClose={onClose}
      size="large"
      title={item ? `${item.number} · ${item.category}` : t("item")}
      extra={
        item &&
        permissions.edit && (
          <Space>
            <Link href={`/projects/${project.id}/qna?new=1&request=${item.id}`}>
              <Button icon={<QuestionCircleOutlined />}>{t("askQuestion")}</Button>
            </Link>
            <Button icon={<EditOutlined />} onClick={() => onEdit(item)}>
              {t("edit")}
            </Button>
            {item.status === "NOT_REQUESTED" && item.requestedAt === null && (
              <Popconfirm
                title={t("deleteConfirm")}
                onConfirm={() =>
                  startTransition(async () => {
                    const result = await deleteItem(project.id, item.id);
                    if (!result.ok) {
                      message.error(result.message || t("error"));
                      return;
                    }
                    router.refresh();
                    onClose();
                  })
                }
              >
                <Button danger icon={<DeleteOutlined />} aria-label={t("delete")} />
              </Popconfirm>
            )}
          </Space>
        )
      }
    >
      {!detail ? (
        loaded?.id === itemId && loaded.error !== undefined ? (
          <Alert type="error" showIcon title={loaded.error || t("error")} />
        ) : (
          <Flex justify="center" style={{ padding: 48 }}>
            <Spin />
          </Flex>
        )
      ) : (
        item && (
          <Flex vertical gap={16}>
            <Typography.Paragraph style={{ whiteSpace: "pre-wrap", margin: 0 }}>
              {item.description}
            </Typography.Paragraph>
            <Descriptions size="small" column={2} bordered>
              <Descriptions.Item label={t("status")}>
                <Tag color={STATUS_COLORS[item.status]}>{t(`statusValues.${item.status}`)}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t("priority")}>
                <Tag color={PRIORITY_COLORS[item.priority]}>{t(`priorityValues.${item.priority}`)}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t("workstream")}>
                {item.workstream ? tw(item.workstream) : null}
              </Descriptions.Item>
              <Descriptions.Item label={t("wave")}>{item.wave}</Descriptions.Item>
              <Descriptions.Item label={t("dueDate")}>
                {item.dueDate
                  ? format.dateTime(new Date(`${item.dueDate}T00:00:00`), { dateStyle: "medium" })
                  : "—"}
              </Descriptions.Item>
              <Descriptions.Item label={t("assignee")}>
                {item.assigneeName ?? t("unassigned")}
              </Descriptions.Item>
            </Descriptions>

            {statuses.length > 0 && (
              <Flex gap={8} wrap align="center">
                <Typography.Text type="secondary">
                  {permissions.deliver ? t("submitAs") : t("moveTo")}
                </Typography.Text>
                {statuses.map((status) => (
                  <Button
                    key={status}
                    size="small"
                    loading={pending}
                    danger={status === "REJECTED"}
                    type={status === "RECEIVED" || status === "CLOSED" ? "primary" : "default"}
                    onClick={() => moveTo(status)}
                  >
                    {permissions.deliver ? t(`submit.${status}`) : t(`statusValues.${status}`)}
                  </Button>
                ))}
              </Flex>
            )}
            {permissions.deliver && statuses.length === 0 && canDeliver && (
              <Alert type="info" showIcon title={t("linkFirst")} />
            )}

            <Divider titlePlacement="start" style={{ margin: 0 }}>
              {t("documents")} ({detail.documents.length})
            </Divider>
            {detail.documents.length === 0 ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t("noDocuments")} />
            ) : (
              <List
                size="small"
                dataSource={detail.documents}
                renderItem={(doc) => (
                  <List.Item
                    actions={[
                      <Button
                        key="view"
                        size="small"
                        icon={<EyeOutlined />}
                        disabled={doc.scanStatus !== "CLEAN"}
                        onClick={() => setViewing({ documentId: doc.id })}
                        aria-label={t("view")}
                      />,
                      canDeliver && (
                        <Popconfirm
                          key="unlink"
                          title={t("unlinkConfirm")}
                          onConfirm={() => act(() => unlinkDocument(project.id, item.id, doc.id))}
                        >
                          <Button size="small" icon={<DeleteOutlined />} aria-label={t("unlink")} />
                        </Popconfirm>
                      ),
                    ]}
                  >
                    <List.Item.Meta
                      title={
                        <Space>
                          {doc.name}
                          {doc.scanStatus !== "CLEAN" && <Tag>{doc.scanStatus}</Tag>}
                        </Space>
                      }
                      description={
                        <>
                          {doc.comment && <div>{doc.comment}</div>}
                          <Typography.Text type="secondary">
                            {formatBytes(doc.sizeBytes)} · {doc.linkedByName ?? "—"} ·{" "}
                            {format.dateTime(new Date(doc.linkedAt), {
                              dateStyle: "medium",
                              timeStyle: "short",
                            })}
                          </Typography.Text>
                        </>
                      }
                    />
                  </List.Item>
                )}
              />
            )}
            {detail.hiddenDocuments > 0 && (
              <Typography.Text type="secondary">
                {t("hiddenDocuments", { count: detail.hiddenDocuments })}
              </Typography.Text>
            )}
            {canDeliver && <DeliveryPanel projectId={project.id} itemId={item.id} onLinked={reload} />}

            <Divider titlePlacement="start" style={{ margin: 0 }}>
              {t("comments")} ({detail.comments.length})
            </Divider>
            <CommentThread
              projectId={project.id}
              detail={detail}
              canComment={(permissions.deliver || permissions.edit) && project.status === "ACTIVE"}
              canWriteInternal={permissions.edit}
              onAdded={reload}
            />

            <Divider titlePlacement="start" style={{ margin: 0 }}>
              {t("history")}
            </Divider>
            <Timeline
              items={detail.history.map((h) => ({
                color: STATUS_COLORS[h.toStatus] === "default" ? "gray" : undefined,
                children: (
                  <>
                    <Typography.Text strong>{t(`statusValues.${h.toStatus}`)}</Typography.Text>{" "}
                    <Typography.Text type="secondary">
                      · {h.changedByName ?? t("system")} ·{" "}
                      {format.dateTime(new Date(h.changedAt), { dateStyle: "medium", timeStyle: "short" })}
                    </Typography.Text>
                    {h.note && (
                      <div>
                        {h.note === "template" || h.note === "import" ? t(`origin.${h.note}`) : h.note}
                      </div>
                    )}
                  </>
                ),
              }))}
            />
          </Flex>
        )
      )}
      <DocumentViewer projectId={project.id} target={viewing} onClose={() => setViewing(null)} />
      {rejecting && item && (
        <RejectModal
          onCancel={() => setRejecting(false)}
          onSubmit={(reason) => {
            setRejecting(false);
            act(
              () => changeStatus(project.id, item.id, "REJECTED", reason),
              t("statusChanged", { status: t("statusValues.REJECTED") }),
            );
          }}
        />
      )}
    </Drawer>
  );
}

function RejectModal({
  onCancel,
  onSubmit,
}: Readonly<{ onCancel: () => void; onSubmit: (reason: string) => void }>) {
  const t = useTranslations("requests");
  const [reason, setReason] = useState("");
  return (
    <Modal
      open
      title={t("rejectTitle")}
      okText={t("reject")}
      okButtonProps={{ danger: true, disabled: reason.trim() === "" }}
      onOk={() => onSubmit(reason.trim())}
      onCancel={onCancel}
    >
      <Typography.Paragraph type="secondary">{t("rejectHelp")}</Typography.Paragraph>
      <Input.TextArea rows={4} maxLength={4000} value={reason} onChange={(e) => setReason(e.target.value)} />
    </Modal>
  );
}

/** Link a delivered document: upload a new file into a data-room folder or pick one that is already there. */
function DeliveryPanel({
  projectId,
  itemId,
  onLinked,
}: Readonly<{ projectId: string; itemId: string; onLinked: () => void }>) {
  const t = useTranslations("requests");
  const { message } = App.useApp();
  const [folders, setFolders] = useState<Folder[] | null>(null);
  const [folderId, setFolderId] = useState<string | undefined>();
  const [documents, setDocuments] = useState<DataRoomDocument[]>([]);
  const [documentId, setDocumentId] = useState<string | undefined>();
  const [comment, setComment] = useState("");
  const [busy, startTransition] = useTransition();

  useEffect(() => {
    let active = true;
    void loadFolders(projectId).then((result) => {
      if (active) setFolders(result.ok ? (result.data ?? []) : []);
    });
    return () => {
      active = false;
    };
  }, [projectId]);

  useEffect(() => {
    if (!folderId) return undefined;
    let active = true;
    void loadFolderDocuments(projectId, folderId).then((result) => {
      if (active) setDocuments(result.ok ? (result.data ?? []) : []);
    });
    return () => {
      active = false;
    };
  }, [projectId, folderId]);

  const treeData = useMemo(() => {
    const toNode = (node: ReturnType<typeof buildTree>[number]): object => ({
      value: node.folder.id,
      title: node.folder.name,
      children: node.children.map(toNode),
    });
    return folders ? buildTree(folders).map(toNode) : [];
  }, [folders]);
  const folder = folders?.find((f) => f.id === folderId);

  const link = (id: string) =>
    startTransition(async () => {
      const result = await linkDocument(projectId, itemId, id, comment.trim() || undefined);
      if (!result.ok) {
        message.error(result.message || t("error"));
        return;
      }
      message.success(t("linked"));
      setDocumentId(undefined);
      setComment("");
      onLinked();
    });

  const customRequest: UploadProps["customRequest"] = ({ file, onSuccess, onError }) => {
    if (!folderId) return;
    const upload = file as File;
    const body = new FormData();
    body.append("file", upload);
    void fetch(
      `/api/projects/${encodeURIComponent(projectId)}/folders/${encodeURIComponent(folderId)}/documents`,
      {
        method: "POST",
        body,
      },
    ).then(async (response) => {
      const payload = (await response.json().catch(() => ({}))) as UploadResponse & { detail?: string };
      if (!response.ok) {
        message.error(
          payload.detail ? `${upload.name}: ${payload.detail}` : t("uploadFailed", { name: upload.name }),
        );
        onError?.(new Error(payload.detail ?? `HTTP ${response.status}`));
        return;
      }
      onSuccess?.(payload);
      link(payload.document.id);
    });
  };

  if (folders === null) return <Spin size="small" />;
  if (folders.length === 0) return <Alert type="warning" showIcon title={t("noFolders")} />;
  return (
    <Flex vertical gap={8} style={{ padding: 12, border: "1px dashed #d9d9d9", borderRadius: 8 }}>
      <Typography.Text strong>{t("deliver")}</Typography.Text>
      <TreeSelect
        placeholder={t("chooseFolder")}
        treeData={treeData}
        value={folderId}
        onChange={(value: string) => {
          setFolderId(value);
          setDocumentId(undefined);
        }}
        treeDefaultExpandAll
        style={{ width: "100%" }}
      />
      <Input
        placeholder={t("documentNote")}
        maxLength={4000}
        value={comment}
        onChange={(e) => setComment(e.target.value)}
      />
      <Flex gap={8} wrap>
        <Upload customRequest={customRequest} showUploadList={false} disabled={!folder?.canUpload || busy}>
          <Button icon={<UploadOutlined />} disabled={!folder?.canUpload} loading={busy}>
            {t("uploadAndLink")}
          </Button>
        </Upload>
        <Select
          showSearch={{ optionFilterProp: "label" }}
          placeholder={t("chooseExisting")}
          disabled={!folderId}
          value={documentId}
          onChange={setDocumentId}
          style={{ minWidth: 220, flex: 1 }}
          options={documents.map((d) => ({ value: d.id, label: d.name }))}
        />
        <Button
          icon={<LinkOutlined />}
          disabled={!documentId}
          loading={busy}
          onClick={() => documentId && link(documentId)}
        >
          {t("link")}
        </Button>
      </Flex>
      {folder && !folder.canUpload && <Typography.Text type="secondary">{t("noUploadHere")}</Typography.Text>}
    </Flex>
  );
}

function CommentThread({
  projectId,
  detail,
  canComment,
  canWriteInternal,
  onAdded,
}: Readonly<{
  projectId: string;
  detail: RequestItemDetail;
  canComment: boolean;
  canWriteInternal: boolean;
  onAdded: () => void;
}>) {
  const t = useTranslations("requests");
  const format = useFormatter();
  const { message } = App.useApp();
  const [body, setBody] = useState("");
  const [internal, setInternal] = useState(false);
  const [busy, startTransition] = useTransition();

  const send = () =>
    startTransition(async () => {
      const result = await addComment(projectId, detail.item.id, body.trim(), internal);
      if (!result.ok) {
        message.error(result.message || t("error"));
        return;
      }
      setBody("");
      onAdded();
    });

  return (
    <Flex vertical gap={8}>
      {detail.comments.length === 0 && <Typography.Text type="secondary">{t("noComments")}</Typography.Text>}
      {detail.comments.map((c) => (
        <div
          key={c.id}
          style={{
            padding: "8px 12px",
            borderRadius: 8,
            background: c.internal ? "rgba(250, 173, 20, 0.08)" : "rgba(0, 0, 0, 0.02)",
          }}
        >
          <Space size={4}>
            <Typography.Text strong>{c.authorName ?? "—"}</Typography.Text>
            {c.internal && (
              <Tag icon={<LockOutlined />} color="gold">
                {t("internalNote")}
              </Tag>
            )}
            <Typography.Text type="secondary">
              {format.dateTime(new Date(c.createdAt), { dateStyle: "medium", timeStyle: "short" })}
            </Typography.Text>
          </Space>
          <Typography.Paragraph style={{ whiteSpace: "pre-wrap", margin: 0 }}>{c.body}</Typography.Paragraph>
        </div>
      ))}
      {canComment && (
        <>
          <Input.TextArea
            rows={3}
            maxLength={4000}
            value={body}
            placeholder={t("commentPlaceholder")}
            onChange={(e) => setBody(e.target.value)}
          />
          <Flex justify="space-between" align="center">
            {canWriteInternal ? (
              <Checkbox checked={internal} onChange={(e) => setInternal(e.target.checked)}>
                {t("internalOnly")}
              </Checkbox>
            ) : (
              <span />
            )}
            <Button type="primary" disabled={body.trim() === ""} loading={busy} onClick={send}>
              {t("send")}
            </Button>
          </Flex>
        </>
      )}
    </Flex>
  );
}
