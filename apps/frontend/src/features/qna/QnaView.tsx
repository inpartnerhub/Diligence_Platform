"use client";

import {
  ArrowLeftOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EditOutlined,
  EyeOutlined,
  FileExcelOutlined,
  FilePdfOutlined,
  PlusOutlined,
} from "@ant-design/icons";
import {
  Alert,
  App,
  Button,
  Card,
  DatePicker,
  Descriptions,
  Divider,
  Drawer,
  Dropdown,
  Empty,
  Flex,
  Form,
  Input,
  List,
  Modal,
  Popconfirm,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  Timeline,
  Typography,
} from "antd";
import type { TableColumnsType } from "antd";
import dayjs, { type Dayjs } from "dayjs";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useFormatter, useTranslations } from "next-intl";
import { useCallback, useEffect, useState, useTransition } from "react";

import { DocumentSource } from "@/features/dataroom/DocumentSource";
import { DocumentViewer, type ViewerTarget } from "@/features/dataroom/DocumentViewer";
import type { Member, Project, Workstream } from "@/features/projects/types";
import { useUrlQuery } from "@/lib/navigation/useUrlQuery";

import {
  answerQuestion,
  changeQuestionStatus,
  createQuestion,
  deleteQuestion,
  linkAnswerDocument,
  loadQuestion,
  unlinkAnswerDocument,
  updateQuestion,
} from "./actions";
import {
  QUESTION_STATUSES,
  STATUS_COLORS,
  moveKey,
  needsFeedback,
  teamMoves,
  type QnaPermissions,
  type Question,
  type QuestionDetail,
  type QuestionInput,
  type QuestionStatus,
} from "./types";

/** Pre-filled links when a question is asked from a request item, a finding or a document. */
export interface QuestionSource {
  requestItemId?: string;
  findingId?: string;
  documentId?: string;
}

export interface QnaViewProps {
  project: Project;
  questions: Question[];
  workstreams: Workstream[];
  targetMembers: Member[];
  permissions: QnaPermissions;
  filters: { status?: string; q?: string };
  selectedId: string | null;
  source: QuestionSource | null;
}

/** Q&A of a project (PRD QNA-01…QNA-03): the team's approval queue, the target's inbox, the client's questions. */
export function QnaView({
  project,
  questions,
  workstreams,
  targetMembers,
  permissions,
  filters,
  selectedId,
  source,
}: Readonly<QnaViewProps>) {
  const t = useTranslations("qna");
  const format = useFormatter();
  const { update } = useUrlQuery();
  const [editing, setEditing] = useState<Question | "new" | null>(source && permissions.ask ? "new" : null);
  const pending = questions.filter((q) => q.status === "PENDING_APPROVAL").length;

  const columns: TableColumnsType<Question> = [
    { title: t("number"), dataIndex: "number", width: 90 },
    { title: t("question"), dataIndex: "body", ellipsis: true },
    {
      title: t("status"),
      dataIndex: "status",
      width: 170,
      render: (status: QuestionStatus) => (
        <Tag color={STATUS_COLORS[status]}>{t(`statusValues.${status}`)}</Tag>
      ),
    },
    { title: t("askedBy"), dataIndex: "askedByName", width: 160, ellipsis: true },
    {
      title: t("dueDate"),
      dataIndex: "dueDate",
      width: 120,
      render: (value: string | null) =>
        value ? format.dateTime(new Date(`${value}T00:00:00`), { dateStyle: "medium" }) : null,
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
          {permissions.ask && (
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setEditing("new")}>
              {t("newQuestion")}
            </Button>
          )}
          <Dropdown
            menu={{
              items: [
                {
                  key: "xlsx",
                  icon: <FileExcelOutlined />,
                  label: (
                    <a href={`/api/projects/${project.id}/questions/export?format=xlsx`}>
                      {t("exportExcel")}
                    </a>
                  ),
                },
                {
                  key: "pdf",
                  icon: <FilePdfOutlined />,
                  label: (
                    <a href={`/api/projects/${project.id}/questions/export?format=pdf`}>{t("exportPdf")}</a>
                  ),
                },
              ],
            }}
          >
            <Button icon={<DownloadOutlined />}>{t("export")}</Button>
          </Dropdown>
        </Space>
      </Flex>
      {permissions.answer && (
        <Alert type="info" showIcon style={{ marginBottom: 16 }} title={t("targetHint")} />
      )}
      {!permissions.internal && !permissions.answer && (
        <Alert type="info" showIcon style={{ marginBottom: 16 }} title={t("clientHint")} />
      )}
      {permissions.manage && pending > 0 && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          title={t("pendingApproval", { count: pending })}
        />
      )}

      <Card>
        <Flex gap={8} wrap style={{ marginBottom: 16 }}>
          <Input.Search
            allowClear
            defaultValue={filters.q}
            placeholder={t("searchPlaceholder")}
            style={{ width: 280 }}
            onSearch={(q) => update({ q })}
          />
          <Select
            allowClear
            placeholder={t("status")}
            value={filters.status}
            style={{ width: 200 }}
            options={QUESTION_STATUSES.map((s) => ({ value: s, label: t(`statusValues.${s}`) }))}
            onChange={(status?: string) => update({ status })}
          />
        </Flex>
        <Table<Question>
          rowKey="id"
          size="middle"
          columns={columns}
          dataSource={questions}
          scroll={{ x: 800 }}
          pagination={{ pageSize: 50, hideOnSinglePage: true, showSizeChanger: false }}
          locale={{ emptyText: permissions.answer ? t("emptyTarget") : t("empty") }}
          onRow={(q) => ({ onClick: () => update({ question: q.id }, false), style: { cursor: "pointer" } })}
        />
      </Card>

      <QuestionDrawer
        project={project}
        questionId={selectedId}
        permissions={permissions}
        onEdit={setEditing}
        onClose={() => update({ question: undefined }, false)}
      />
      {editing && (
        <QuestionFormModal
          projectId={project.id}
          question={editing === "new" ? null : editing}
          workstreams={workstreams}
          targetMembers={targetMembers}
          permissions={permissions}
          source={editing === "new" ? source : null}
          onClose={() => {
            setEditing(null);
            if (source)
              update({ new: undefined, request: undefined, finding: undefined, document: undefined }, false);
          }}
        />
      )}
    </>
  );
}

interface FormValues {
  body: string;
  workstreamId?: string | null;
  recipientUserId?: string | null;
  dueDate?: Dayjs | null;
}

function QuestionFormModal({
  projectId,
  question,
  workstreams,
  targetMembers,
  permissions,
  source,
  onClose,
}: Readonly<{
  projectId: string;
  question: Question | null;
  workstreams: Workstream[];
  targetMembers: Member[];
  permissions: QnaPermissions;
  source: QuestionSource | null;
  onClose: () => void;
}>) {
  const t = useTranslations("qna");
  const tw = useTranslations("workstreams");
  const { message } = App.useApp();
  const router = useRouter();
  const [form] = Form.useForm<FormValues>();
  const [pending, startTransition] = useTransition();
  const workstreamId = Form.useWatch("workstreamId", form);
  const recipients = targetMembers.filter(
    (m) => !workstreamId || m.workstreamId === null || m.workstreamId === workstreamId,
  );

  const save = (submit: boolean) =>
    form.validateFields().then((values) =>
      startTransition(async () => {
        const input: QuestionInput = {
          body: values.body,
          workstreamId: values.workstreamId ?? null,
          recipientUserId: values.recipientUserId ?? null,
          dueDate: values.dueDate ? values.dueDate.format("YYYY-MM-DD") : null,
          requestItemId: question?.requestItemId ?? source?.requestItemId ?? null,
          findingId: question?.findingId ?? source?.findingId ?? null,
          documentId: question?.documentId ?? source?.documentId ?? null,
          submit,
        };
        const result = question
          ? await updateQuestion(projectId, question.id, input)
          : await createQuestion(projectId, input);
        if (!result.ok) {
          message.error(result.message || t("error"));
          return;
        }
        message.success(question ? t("saved") : t("created", { number: result.data?.number ?? "" }));
        router.refresh();
        onClose();
      }),
    );

  return (
    <Modal
      open
      width={640}
      title={question ? t("editTitle", { number: question.number }) : t("newQuestion")}
      onCancel={onClose}
      destroyOnHidden
      footer={
        <Space>
          <Button onClick={onClose}>{t("cancel")}</Button>
          {permissions.edit && !question && (
            <Button loading={pending} onClick={() => void save(false)}>
              {t("saveDraft")}
            </Button>
          )}
          <Button type="primary" loading={pending} onClick={() => void save(!question)}>
            {question ? t("save") : permissions.edit ? t("submitForApproval") : t("send")}
          </Button>
        </Space>
      }
    >
      {source && <Alert type="info" showIcon style={{ marginBottom: 16 }} title={t("fromSource")} />}
      {!permissions.edit && (
        <Alert type="info" showIcon style={{ marginBottom: 16 }} title={t("clientApproval")} />
      )}
      <Form<FormValues>
        form={form}
        layout="vertical"
        initialValues={
          question
            ? {
                body: question.body,
                workstreamId: question.workstreamId,
                recipientUserId: question.recipientUserId,
                dueDate: question.dueDate ? dayjs(question.dueDate) : null,
              }
            : {}
        }
      >
        <Form.Item name="body" label={t("question")} rules={[{ required: true, whitespace: true }]}>
          <Input.TextArea rows={5} maxLength={4000} showCount />
        </Form.Item>
        {permissions.edit && (
          <Flex gap={16} wrap>
            <Form.Item name="workstreamId" label={t("workstream")} style={{ minWidth: 200 }}>
              <Select allowClear options={workstreams.map((w) => ({ value: w.id, label: tw(w.type) }))} />
            </Form.Item>
            <Form.Item name="recipientUserId" label={t("recipient")} style={{ minWidth: 200, flex: 1 }}>
              <Select
                allowClear
                placeholder={t("allTargets")}
                options={recipients.map((m) => ({
                  value: m.userId,
                  label: m.displayName ?? m.email ?? m.userId,
                }))}
              />
            </Form.Item>
            <Form.Item name="dueDate" label={t("dueDate")}>
              <DatePicker />
            </Form.Item>
          </Flex>
        )}
      </Form>
    </Modal>
  );
}

function QuestionDrawer({
  project,
  questionId,
  permissions,
  onEdit,
  onClose,
}: Readonly<{
  project: Project;
  questionId: string | null;
  permissions: QnaPermissions;
  onEdit: (question: Question) => void;
  onClose: () => void;
}>) {
  const t = useTranslations("qna");
  const format = useFormatter();
  const router = useRouter();
  const { message } = App.useApp();
  const [loaded, setLoaded] = useState<{ id: string; detail: QuestionDetail | null; error?: string } | null>(
    null,
  );
  const [version, setVersion] = useState(0);
  const [viewing, setViewing] = useState<ViewerTarget | null>(null);
  const [moving, setMoving] = useState<QuestionStatus | null>(null);
  const [pending, startTransition] = useTransition();

  useEffect(() => {
    if (!questionId) return undefined;
    let active = true;
    void loadQuestion(project.id, questionId).then((result) => {
      if (active) {
        setLoaded({
          id: questionId,
          detail: result.ok ? (result.data ?? null) : null,
          error: result.ok ? undefined : result.message,
        });
      }
    });
    return () => {
      active = false;
    };
  }, [project.id, questionId, version]);

  const reload = useCallback(() => {
    setVersion((v) => v + 1);
    router.refresh();
  }, [router]);

  const detail = loaded?.id === questionId ? loaded.detail : null;
  const question = detail?.question;
  const date = (value: string) =>
    format.dateTime(new Date(value), { dateStyle: "medium", timeStyle: "short" });
  const answerable = question?.status === "SENT" || question?.status === "ANSWERED";

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

  const move = (to: QuestionStatus, feedback?: string) => {
    if (!question) return;
    act(
      () => changeQuestionStatus(project.id, question.id, to, feedback),
      t("statusChanged", { status: t(`statusValues.${to}`) }),
    );
  };

  return (
    <Drawer
      open={questionId !== null}
      onClose={onClose}
      size="large"
      title={question ? `${question.number} · ${t(`statusValues.${question.status}`)}` : t("question")}
      extra={
        question &&
        permissions.edit &&
        (question.status === "DRAFT" || question.status === "PENDING_APPROVAL") && (
          <Space>
            <Button icon={<EditOutlined />} onClick={() => onEdit(question)}>
              {t("edit")}
            </Button>
            {question.status === "DRAFT" && question.sentAt === null && (
              <Popconfirm
                title={t("deleteConfirm")}
                onConfirm={() =>
                  startTransition(async () => {
                    const result = await deleteQuestion(project.id, question.id);
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
        loaded?.id === questionId && loaded.error !== undefined ? (
          <Alert type="error" showIcon title={loaded.error || t("error")} />
        ) : (
          <Flex justify="center" style={{ padding: 48 }}>
            <Spin />
          </Flex>
        )
      ) : (
        question && (
          <Flex vertical gap={16}>
            <Typography.Paragraph style={{ whiteSpace: "pre-wrap", margin: 0, fontSize: 15 }}>
              {question.body}
            </Typography.Paragraph>
            <Descriptions size="small" column={2} bordered>
              <Descriptions.Item label={t("status")}>
                <Tag color={STATUS_COLORS[question.status]}>{t(`statusValues.${question.status}`)}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t("askedBy")}>{question.askedByName ?? "—"}</Descriptions.Item>
              {question.recipientName && (
                <Descriptions.Item label={t("recipient")}>{question.recipientName}</Descriptions.Item>
              )}
              {question.dueDate && (
                <Descriptions.Item label={t("dueDate")}>
                  {format.dateTime(new Date(`${question.dueDate}T00:00:00`), { dateStyle: "medium" })}
                </Descriptions.Item>
              )}
              {detail.references.requestItemNumber && (
                <Descriptions.Item label={t("requestItem")}>
                  {detail.references.requestItemNumber}
                </Descriptions.Item>
              )}
              {detail.references.documentName && (
                <Descriptions.Item label={t("document")}>{detail.references.documentName}</Descriptions.Item>
              )}
              {detail.references.findingNumber && (
                <Descriptions.Item label={t("finding")}>{detail.references.findingNumber}</Descriptions.Item>
              )}
            </Descriptions>
            {question.feedback && (
              <Alert type="warning" showIcon title={t("feedback")} description={question.feedback} />
            )}

            {teamMoves(question.status, permissions).length > 0 && (
              <Flex gap={8} wrap align="center">
                <Typography.Text type="secondary">{t("moveTo")}</Typography.Text>
                {teamMoves(question.status, permissions).map((to) => (
                  <Button
                    key={to}
                    size="small"
                    loading={pending}
                    type={to === "SENT" && question.status !== "ANSWERED" ? "primary" : "default"}
                    onClick={() => (needsFeedback(question.status, to) ? setMoving(to) : move(to))}
                  >
                    {t(`moves.${moveKey(question.status, to)}`)}
                  </Button>
                ))}
              </Flex>
            )}

            <Divider titlePlacement="start" style={{ margin: 0 }}>
              {t("answer")}
            </Divider>
            {detail.answer ? (
              <div style={{ padding: "8px 12px", borderRadius: 8, background: "rgba(0, 0, 0, 0.02)" }}>
                <Typography.Text type="secondary">
                  {detail.answer.answeredByName ?? "—"} · {date(detail.answer.answeredAt)}
                </Typography.Text>
                <Typography.Paragraph style={{ whiteSpace: "pre-wrap", margin: 0 }}>
                  {detail.answer.body}
                </Typography.Paragraph>
              </div>
            ) : (
              <Typography.Text type="secondary">
                {!permissions.internal && !permissions.answer ? t("answerAfterReview") : t("noAnswer")}
              </Typography.Text>
            )}
            {permissions.answer && answerable && (
              <AnswerForm
                key={detail.answer?.answeredAt ?? "new"}
                initial={detail.answer?.body ?? ""}
                onSubmit={(body) => act(() => answerQuestion(project.id, question.id, body), t("answered"))}
                busy={pending}
              />
            )}

            {(permissions.internal || permissions.answer) && (
              <>
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
                          answerable && (permissions.answer || permissions.edit) && (
                            <Popconfirm
                              key="unlink"
                              title={t("unlinkConfirm")}
                              onConfirm={() =>
                                act(() => unlinkAnswerDocument(project.id, question.id, doc.id))
                              }
                            >
                              <Button size="small" icon={<DeleteOutlined />} aria-label={t("unlink")} />
                            </Popconfirm>
                          ),
                        ]}
                      >
                        <List.Item.Meta
                          title={doc.name}
                          description={`${doc.linkedByName ?? "—"} · ${date(doc.linkedAt)}`}
                        />
                      </List.Item>
                    )}
                  />
                )}
                {answerable && (permissions.answer || permissions.edit) && (
                  <DocumentSource
                    projectId={project.id}
                    onChoose={(documentId) =>
                      act(() => linkAnswerDocument(project.id, question.id, documentId))
                    }
                    busy={pending}
                  />
                )}
              </>
            )}

            {permissions.internal && (
              <>
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
          </Flex>
        )
      )}
      <DocumentViewer projectId={project.id} target={viewing} onClose={() => setViewing(null)} />
      {moving && question && (
        <FeedbackModal
          title={t(`moves.${moveKey(question.status, moving)}`)}
          onCancel={() => setMoving(null)}
          onSubmit={(feedback) => {
            setMoving(null);
            move(moving, feedback);
          }}
        />
      )}
    </Drawer>
  );
}

function AnswerForm({
  initial,
  onSubmit,
  busy,
}: Readonly<{ initial: string; onSubmit: (body: string) => void; busy: boolean }>) {
  const t = useTranslations("qna");
  const [body, setBody] = useState(initial);
  return (
    <Flex vertical gap={8}>
      <Input.TextArea
        rows={5}
        maxLength={10000}
        value={body}
        placeholder={t("answerPlaceholder")}
        onChange={(e) => setBody(e.target.value)}
      />
      <Flex justify="end">
        <Button
          type="primary"
          disabled={body.trim() === "" || body === initial}
          loading={busy}
          onClick={() => onSubmit(body.trim())}
        >
          {initial ? t("updateAnswer") : t("sendAnswer")}
        </Button>
      </Flex>
    </Flex>
  );
}

function FeedbackModal({
  title,
  onCancel,
  onSubmit,
}: Readonly<{ title: string; onCancel: () => void; onSubmit: (feedback: string) => void }>) {
  const t = useTranslations("qna");
  const [feedback, setFeedback] = useState("");
  return (
    <Modal
      open
      title={title}
      okText={title}
      okButtonProps={{ disabled: feedback.trim() === "" }}
      onOk={() => onSubmit(feedback.trim())}
      onCancel={onCancel}
    >
      <Typography.Paragraph type="secondary">{t("feedbackHelp")}</Typography.Paragraph>
      <Input.TextArea
        rows={4}
        maxLength={4000}
        value={feedback}
        onChange={(e) => setFeedback(e.target.value)}
      />
    </Modal>
  );
}
