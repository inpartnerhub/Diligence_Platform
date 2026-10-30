"use client";

import { DeleteOutlined, EditOutlined, EyeOutlined, LinkOutlined } from "@ant-design/icons";
import {
  Alert,
  App,
  Button,
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
} from "antd";
import { useRouter } from "next/navigation";
import { useFormatter, useTranslations } from "next-intl";
import { useCallback, useEffect, useMemo, useState, useTransition } from "react";

import { DocumentViewer, type ViewerTarget } from "@/features/dataroom/DocumentViewer";
import { buildTree, type DataRoomDocument, type Folder } from "@/features/dataroom/types";
import type { Project } from "@/features/projects/types";
import { loadFolderDocuments, loadFolders } from "@/features/requests/actions";

import {
  addFindingNote,
  changeFindingStatus,
  deleteFinding,
  linkFindingDocument,
  loadFinding,
  unlinkFindingDocument,
} from "./actions";
import { RiskTag } from "./RiskTag";
import {
  STATUS_COLORS,
  needsReason,
  nextStatuses,
  type Finding,
  type FindingDetail,
  type FindingPermissions,
  type FindingStatus,
} from "./types";

export function FindingDrawer({
  project,
  findingId,
  permissions,
  internal,
  userId,
  onEdit,
  onClose,
}: Readonly<{
  project: Project;
  findingId: string | null;
  permissions: FindingPermissions;
  internal: boolean;
  userId: string;
  onEdit: (finding: Finding) => void;
  onClose: () => void;
}>) {
  const t = useTranslations("findings");
  const tw = useTranslations("workstreams");
  const format = useFormatter();
  const router = useRouter();
  const { message } = App.useApp();
  const [loaded, setLoaded] = useState<{ id: string; detail: FindingDetail | null; error?: string } | null>(
    null,
  );
  const [version, setVersion] = useState(0);
  const [viewing, setViewing] = useState<ViewerTarget | null>(null);
  const [moving, setMoving] = useState<FindingStatus | null>(null);
  const [pending, startTransition] = useTransition();

  useEffect(() => {
    if (!findingId) return undefined;
    let active = true;
    void loadFinding(project.id, findingId).then((result) => {
      if (active) {
        setLoaded({
          id: findingId,
          detail: result.ok ? (result.data ?? null) : null,
          error: result.ok ? undefined : result.message,
        });
      }
    });
    return () => {
      active = false;
    };
  }, [project.id, findingId, version]);

  const reload = useCallback(() => {
    setVersion((v) => v + 1);
    router.refresh();
  }, [router]);

  const detail = loaded?.id === findingId ? loaded.detail : null;
  const finding = detail?.finding;
  const draft = finding?.status === "DRAFT" && permissions.edit;

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

  const move = (status: FindingStatus, reason?: string) => {
    if (!finding) return;
    act(
      () => changeFindingStatus(project.id, finding.id, status, reason),
      t("statusChanged", { status: t(`statusValues.${status}`) }),
    );
  };

  const statuses = finding ? nextStatuses(finding, permissions, userId) : [];
  const date = (value: string) =>
    format.dateTime(new Date(value), { dateStyle: "medium", timeStyle: "short" });

  return (
    <Drawer
      open={findingId !== null}
      onClose={onClose}
      size="large"
      title={finding ? `${finding.number} · ${finding.title}` : t("finding")}
      extra={
        finding &&
        draft && (
          <Space>
            <Button icon={<EditOutlined />} onClick={() => onEdit(finding)}>
              {t("edit")}
            </Button>
            {finding.submittedAt === null && (
              <Popconfirm
                title={t("deleteConfirm")}
                onConfirm={() =>
                  startTransition(async () => {
                    const result = await deleteFinding(project.id, finding.id);
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
        loaded?.id === findingId && loaded.error !== undefined ? (
          <Alert type="error" showIcon title={loaded.error || t("error")} />
        ) : (
          <Flex justify="center" style={{ padding: 48 }}>
            <Spin />
          </Flex>
        )
      ) : (
        finding && (
          <Flex vertical gap={16}>
            <Descriptions size="small" column={2} bordered>
              <Descriptions.Item label={t("status")}>
                <Tag color={STATUS_COLORS[finding.status]}>{t(`statusValues.${finding.status}`)}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t("risk")}>
                {finding.riskLevel ? (
                  <Space size={4}>
                    <RiskTag level={finding.riskLevel} score={finding.riskScore} />
                    <Typography.Text type="secondary">
                      {t("rating", { impact: finding.impact ?? 0, likelihood: finding.likelihood ?? 0 })}
                    </Typography.Text>
                  </Space>
                ) : (
                  <Tag>{t("unrated")}</Tag>
                )}
              </Descriptions.Item>
              <Descriptions.Item label={t("workstream")}>
                {finding.workstream ? tw(finding.workstream) : null}
              </Descriptions.Item>
              <Descriptions.Item label={t("category")}>{finding.category}</Descriptions.Item>
              {finding.monetaryImpact !== null && (
                <Descriptions.Item label={t("monetaryImpact")} span={2}>
                  {format.number(finding.monetaryImpact, {
                    style: "currency",
                    currency: finding.currency ?? "IDR",
                    maximumFractionDigits: 0,
                  })}
                </Descriptions.Item>
              )}
              {internal && (
                <Descriptions.Item label={t("author")} span={2}>
                  {finding.createdByName ?? "—"}
                  {finding.approvedByName && ` · ${t("approvedBy", { name: finding.approvedByName })}`}
                  {finding.releasedByName && ` · ${t("releasedBy", { name: finding.releasedByName })}`}
                </Descriptions.Item>
              )}
            </Descriptions>
            <Section title={t("description")} text={finding.description} />
            <Section title={t("impactDescription")} text={finding.impactDescription} />
            <Section title={t("recommendation")} text={finding.recommendation} />
            {finding.archiveReason && (
              <Alert type="warning" showIcon title={t("archived", { reason: finding.archiveReason })} />
            )}

            {statuses.length > 0 && (
              <Flex gap={8} wrap align="center">
                <Typography.Text type="secondary">{t("moveTo")}</Typography.Text>
                {statuses.map((status) => (
                  <Button
                    key={status}
                    size="small"
                    loading={pending}
                    danger={status === "ARCHIVED"}
                    type={
                      status === "IN_REVIEW" || status === "APPROVED" || status === "RELEASED"
                        ? "primary"
                        : "default"
                    }
                    onClick={() => (needsReason(finding.status, status) ? setMoving(status) : move(status))}
                  >
                    {t(`actions.${status}`)}
                  </Button>
                ))}
              </Flex>
            )}
            {draft && statuses.every((s) => s !== "IN_REVIEW") && (
              <Alert type="info" showIcon title={t("submitPrerequisites")} />
            )}
            {finding.status === "IN_REVIEW" && permissions.review && finding.createdBy === userId && (
              <Alert type="info" showIcon title={t("fourEyes")} />
            )}

            {internal && (
              <>
                <Divider titlePlacement="start" style={{ margin: 0 }}>
                  {t("sources")} ({detail.documents.length})
                </Divider>
                {detail.documents.length === 0 ? (
                  <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t("noSources")} />
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
                          draft && (
                            <Popconfirm
                              key="unlink"
                              title={t("unlinkConfirm")}
                              onConfirm={() =>
                                act(() => unlinkFindingDocument(project.id, finding.id, doc.id))
                              }
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
                              {doc.pageReference && <Tag>{doc.pageReference}</Tag>}
                            </Space>
                          }
                          description={
                            <>
                              {doc.note && <div>{doc.note}</div>}
                              <Typography.Text type="secondary">
                                {doc.linkedByName ?? "—"} · {date(doc.linkedAt)}
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
                    {t("hiddenSources", { count: detail.hiddenDocuments })}
                  </Typography.Text>
                )}
                {draft && <SourcePanel projectId={project.id} findingId={finding.id} onLinked={reload} />}

                <Divider titlePlacement="start" style={{ margin: 0 }}>
                  {t("notes")} ({detail.comments.length})
                </Divider>
                <ReviewNotes
                  projectId={project.id}
                  detail={detail}
                  canWrite={permissions.edit && finding.status !== "ARCHIVED"}
                  onAdded={reload}
                />

                <Divider titlePlacement="start" style={{ margin: 0 }}>
                  {t("history")}
                </Divider>
                <Timeline
                  items={detail.history.map((h) => ({
                    children: (
                      <>
                        <Typography.Text strong>{t(`statusValues.${h.toStatus}`)}</Typography.Text>{" "}
                        <Typography.Text type="secondary">
                          · {h.changedByName ?? "—"} · {date(h.changedAt)}
                        </Typography.Text>
                        {h.note && <div>{h.note}</div>}
                      </>
                    ),
                  }))}
                />
              </>
            )}
            {!internal && finding.releasedAt && (
              <Typography.Text type="secondary">
                {t("releasedOn", { date: date(finding.releasedAt) })}
              </Typography.Text>
            )}
          </Flex>
        )
      )}
      {internal && (
        <DocumentViewer projectId={project.id} target={viewing} onClose={() => setViewing(null)} />
      )}
      {moving && finding && (
        <ReasonModal
          status={moving}
          onCancel={() => setMoving(null)}
          onSubmit={(reason) => {
            setMoving(null);
            move(moving, reason);
          }}
        />
      )}
    </Drawer>
  );
}

function Section({ title, text }: Readonly<{ title: string; text: string | null }>) {
  if (!text) return null;
  return (
    <div>
      <Typography.Text strong>{title}</Typography.Text>
      <Typography.Paragraph style={{ whiteSpace: "pre-wrap", margin: 0 }}>{text}</Typography.Paragraph>
    </div>
  );
}

function ReasonModal({
  status,
  onCancel,
  onSubmit,
}: Readonly<{ status: FindingStatus; onCancel: () => void; onSubmit: (reason: string) => void }>) {
  const t = useTranslations("findings");
  const [reason, setReason] = useState("");
  return (
    <Modal
      open
      title={t(`actions.${status}`)}
      okText={t(`actions.${status}`)}
      okButtonProps={{ danger: status === "ARCHIVED", disabled: reason.trim() === "" }}
      onOk={() => onSubmit(reason.trim())}
      onCancel={onCancel}
    >
      <Typography.Paragraph type="secondary">{t("reasonHelp")}</Typography.Paragraph>
      <Input.TextArea rows={4} maxLength={4000} value={reason} onChange={(e) => setReason(e.target.value)} />
    </Modal>
  );
}

/** Cite a data-room document (with page or section) as a source of the finding (FND-02). */
function SourcePanel({
  projectId,
  findingId,
  onLinked,
}: Readonly<{ projectId: string; findingId: string; onLinked: () => void }>) {
  const t = useTranslations("findings");
  const { message } = App.useApp();
  const [folders, setFolders] = useState<Folder[] | null>(null);
  const [folderId, setFolderId] = useState<string | undefined>();
  const [documents, setDocuments] = useState<DataRoomDocument[]>([]);
  const [documentId, setDocumentId] = useState<string | undefined>();
  const [page, setPage] = useState("");
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

  const link = () =>
    startTransition(async () => {
      if (!documentId) return;
      const result = await linkFindingDocument(projectId, findingId, documentId, page.trim() || undefined);
      if (!result.ok) {
        message.error(result.message || t("error"));
        return;
      }
      message.success(t("linked"));
      setDocumentId(undefined);
      setPage("");
      onLinked();
    });

  if (folders === null) return <Spin size="small" />;
  return (
    <Flex vertical gap={8} style={{ padding: 12, border: "1px dashed #d9d9d9", borderRadius: 8 }}>
      <Typography.Text strong>{t("addSource")}</Typography.Text>
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
      <Flex gap={8} wrap>
        <Select
          showSearch={{ optionFilterProp: "label" }}
          placeholder={t("chooseDocument")}
          disabled={!folderId}
          value={documentId}
          onChange={setDocumentId}
          style={{ minWidth: 220, flex: 1 }}
          options={documents
            .filter((d) => d.scanStatus === "CLEAN")
            .map((d) => ({ value: d.id, label: d.name }))}
        />
        <Input
          placeholder={t("pageReference")}
          maxLength={100}
          value={page}
          onChange={(e) => setPage(e.target.value)}
          style={{ width: 140 }}
        />
        <Button icon={<LinkOutlined />} disabled={!documentId} loading={busy} onClick={link}>
          {t("link")}
        </Button>
      </Flex>
    </Flex>
  );
}

function ReviewNotes({
  projectId,
  detail,
  canWrite,
  onAdded,
}: Readonly<{ projectId: string; detail: FindingDetail; canWrite: boolean; onAdded: () => void }>) {
  const t = useTranslations("findings");
  const format = useFormatter();
  const { message } = App.useApp();
  const [body, setBody] = useState("");
  const [busy, startTransition] = useTransition();

  const send = () =>
    startTransition(async () => {
      const result = await addFindingNote(projectId, detail.finding.id, body.trim());
      if (!result.ok) {
        message.error(result.message || t("error"));
        return;
      }
      setBody("");
      onAdded();
    });

  return (
    <Flex vertical gap={8}>
      {detail.comments.length === 0 && <Typography.Text type="secondary">{t("noNotes")}</Typography.Text>}
      {detail.comments.map((c) => (
        <div
          key={c.id}
          style={{ padding: "8px 12px", borderRadius: 8, background: "rgba(250, 173, 20, 0.08)" }}
        >
          <Space size={4}>
            <Typography.Text strong>{c.authorName ?? "—"}</Typography.Text>
            <Typography.Text type="secondary">
              {format.dateTime(new Date(c.createdAt), { dateStyle: "medium", timeStyle: "short" })}
            </Typography.Text>
          </Space>
          <Typography.Paragraph style={{ whiteSpace: "pre-wrap", margin: 0 }}>{c.body}</Typography.Paragraph>
        </div>
      ))}
      {canWrite && (
        <>
          <Input.TextArea
            rows={3}
            maxLength={4000}
            value={body}
            placeholder={t("notePlaceholder")}
            onChange={(e) => setBody(e.target.value)}
          />
          <Flex justify="space-between" align="center">
            <Typography.Text type="secondary">{t("notesInternal")}</Typography.Text>
            <Button type="primary" disabled={body.trim() === ""} loading={busy} onClick={send}>
              {t("send")}
            </Button>
          </Flex>
        </>
      )}
    </Flex>
  );
}
