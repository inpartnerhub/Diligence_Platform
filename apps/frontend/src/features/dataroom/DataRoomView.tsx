"use client";

import {
  ArrowLeftOutlined,
  DeleteOutlined,
  EditOutlined,
  FolderAddOutlined,
  InboxOutlined,
  LockOutlined,
} from "@ant-design/icons";
import {
  App,
  Button,
  Card,
  Col,
  Descriptions,
  Drawer,
  Empty,
  Flex,
  Input,
  Modal,
  Popconfirm,
  Row,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Timeline,
  Tooltip,
  Tree,
  Typography,
  Upload,
} from "antd";
import type { UploadProps } from "antd";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useFormatter, useTranslations } from "next-intl";
import { useEffect, useMemo, useState, useTransition } from "react";

import { searchUsers } from "@/features/projects/actions";
import type { Project, UserSummary } from "@/features/projects/types";
import { useUrlQuery } from "@/lib/navigation/useUrlQuery";

import { createFolder, deleteFolder, loadGrants, loadVersions, saveGrants, updateFolder } from "./actions";
import {
  buildTree,
  formatBytes,
  type DataRoomDocument,
  type DocumentVersion,
  type Folder,
  type Grant,
  type Permission,
  type ScanStatus,
  type UploadResponse,
} from "./types";

const STATUS_COLORS: Record<ScanStatus, string> = {
  PENDING: "processing",
  CLEAN: "success",
  INFECTED: "error",
  REJECTED: "warning",
  FAILED: "default",
};
const REFRESH_WHILE_SCANNING_MS = 4000;

export interface DataRoomViewProps {
  project: Project;
  folders: Folder[];
  selected: Folder | null;
  documents: DataRoomDocument[];
  canManage: boolean;
}

export function DataRoomView({
  project,
  folders,
  selected,
  documents,
  canManage,
}: Readonly<DataRoomViewProps>) {
  const t = useTranslations("dataRoom");
  const router = useRouter();
  const { update } = useUrlQuery();
  const tree = useMemo(() => buildTree(folders), [folders]);
  const [versionsOf, setVersionsOf] = useState<DataRoomDocument | null>(null);

  // Scans run asynchronously; refresh while any document in view is still pending.
  const scanning = documents.some((d) => d.scanStatus === "PENDING");
  useEffect(() => {
    if (!scanning) return undefined;
    const timer = setInterval(() => router.refresh(), REFRESH_WHILE_SCANNING_MS);
    return () => clearInterval(timer);
  }, [scanning, router]);

  return (
    <>
      <Flex vertical gap={4} style={{ marginBottom: 16 }}>
        <Link href={`/projects/${project.id}`}>
          <ArrowLeftOutlined /> {project.name}
        </Link>
        <Typography.Title level={3} style={{ margin: 0 }}>
          {t("title")} · {project.code}
        </Typography.Title>
      </Flex>
      <Row gutter={[16, 16]}>
        <Col xs={24} lg={8} xl={7}>
          <FolderPanel
            project={project}
            tree={tree}
            selected={selected}
            canManage={canManage && project.status === "ACTIVE"}
            onSelect={(folderId) => update({ folder: folderId }, false)}
          />
        </Col>
        <Col xs={24} lg={16} xl={17}>
          {selected ? (
            <Card title={selected.name} extra={selected.downloadAllowed ? null : <LockOutlined />}>
              {selected.canUpload && <UploadArea projectId={project.id} folder={selected} />}
              <DocumentTable documents={documents} onOpen={setVersionsOf} />
            </Card>
          ) : (
            <Card>
              <Empty
                description={
                  folders.length > 0
                    ? t("selectFolder")
                    : project.myRoles.includes("TARGET") && !canManage
                      ? t("noFoldersTarget")
                      : t("noFolders")
                }
              />
            </Card>
          )}
        </Col>
      </Row>
      <VersionsDrawer projectId={project.id} document={versionsOf} onClose={() => setVersionsOf(null)} />
    </>
  );
}

function FolderPanel({
  project,
  tree,
  selected,
  canManage,
  onSelect,
}: Readonly<{
  project: Project;
  tree: ReturnType<typeof buildTree>;
  selected: Folder | null;
  canManage: boolean;
  onSelect: (folderId: string) => void;
}>) {
  const t = useTranslations("dataRoom");
  const common = useTranslations("common");
  const { message } = App.useApp();
  const [pending, startTransition] = useTransition();
  const [nameModal, setNameModal] = useState<{ mode: "root" | "child" | "rename"; value: string } | null>(
    null,
  );
  const [accessOpen, setAccessOpen] = useState(false);

  const feedback = (result: { ok: boolean; message?: string }) => {
    if (result.ok) {
      message.success(common("saved"));
    } else {
      message.error(result.message || common("error"));
    }
    return result.ok;
  };

  const submitName = () =>
    nameModal &&
    startTransition(async () => {
      const name = nameModal.value.trim();
      const result =
        nameModal.mode === "rename" && selected
          ? await updateFolder(project.id, selected.id, { name })
          : await createFolder(project.id, name, nameModal.mode === "child" ? selected?.id : undefined);
      if (feedback(result)) setNameModal(null);
    });

  return (
    <Card
      title={t("folders")}
      extra={
        canManage && (
          <Button
            size="small"
            icon={<FolderAddOutlined />}
            onClick={() => setNameModal({ mode: "root", value: "" })}
          >
            {t("newFolder")}
          </Button>
        )
      }
    >
      {tree.length === 0 ? (
        <Empty description={t("noFolders")} />
      ) : (
        <Tree.DirectoryTree
          treeData={tree}
          selectedKeys={selected ? [selected.id] : []}
          defaultExpandAll
          expandAction="doubleClick"
          onSelect={(keys) => keys[0] && onSelect(String(keys[0]))}
        />
      )}
      {canManage && selected && (
        <Flex vertical gap={12} style={{ marginTop: 16 }}>
          <Space wrap>
            <Button
              size="small"
              icon={<FolderAddOutlined />}
              onClick={() => setNameModal({ mode: "child", value: "" })}
            >
              {t("newSubfolder")}
            </Button>
            <Button
              size="small"
              icon={<EditOutlined />}
              onClick={() => setNameModal({ mode: "rename", value: selected.name })}
            >
              {t("rename")}
            </Button>
            <Button size="small" icon={<LockOutlined />} onClick={() => setAccessOpen(true)}>
              {t("access")}
            </Button>
            <Popconfirm
              title={t("deleteConfirm")}
              okText={common("yes")}
              cancelText={common("no")}
              onConfirm={() =>
                startTransition(async () => void feedback(await deleteFolder(project.id, selected.id)))
              }
            >
              <Button size="small" danger icon={<DeleteOutlined />}>
                {t("delete")}
              </Button>
            </Popconfirm>
          </Space>
          <Space orientation="vertical" size={4}>
            <Space>
              <Switch
                size="small"
                checked={selected.downloadAllowed}
                loading={pending}
                onChange={(downloadAllowed) =>
                  startTransition(
                    async () =>
                      void feedback(await updateFolder(project.id, selected.id, { downloadAllowed })),
                  )
                }
              />
              <Typography.Text>{t("policy")}</Typography.Text>
            </Space>
            <Space>
              <Switch
                size="small"
                checked={selected.printAllowed}
                loading={pending}
                onChange={(printAllowed) =>
                  startTransition(
                    async () => void feedback(await updateFolder(project.id, selected.id, { printAllowed })),
                  )
                }
              />
              <Typography.Text>{t("printPolicy")}</Typography.Text>
            </Space>
            <Typography.Text type="secondary">{t("policyHelp")}</Typography.Text>
          </Space>
        </Flex>
      )}
      <Modal
        title={
          nameModal?.mode === "rename"
            ? t("rename")
            : nameModal?.mode === "child"
              ? t("newSubfolder")
              : t("newFolder")
        }
        open={nameModal !== null}
        okText={common("save")}
        cancelText={common("cancel")}
        confirmLoading={pending}
        okButtonProps={{ disabled: !nameModal?.value.trim() }}
        onOk={submitName}
        onCancel={() => setNameModal(null)}
        destroyOnHidden
      >
        <Input
          autoFocus
          maxLength={200}
          value={nameModal?.value}
          onChange={(e) => setNameModal((m) => (m ? { ...m, value: e.target.value } : m))}
          onPressEnter={submitName}
        />
      </Modal>
      {selected && (
        <AccessModal
          project={project}
          folder={selected}
          open={accessOpen}
          onClose={() => setAccessOpen(false)}
          onSaved={feedback}
        />
      )}
    </Card>
  );
}

function AccessModal({
  project,
  folder,
  open,
  onClose,
  onSaved,
}: Readonly<{
  project: Project;
  folder: Folder;
  open: boolean;
  onClose: () => void;
  onSaved: (result: { ok: boolean; message?: string }) => boolean;
}>) {
  const t = useTranslations("dataRoom");
  const common = useTranslations("common");
  const [pending, startTransition] = useTransition();
  const [targetPermission, setTargetPermission] = useState<Permission | "NONE">("NONE");
  const [userGrants, setUserGrants] = useState<Grant[]>([]);
  const [otherGrants, setOtherGrants] = useState<Grant[]>([]);
  const [candidates, setCandidates] = useState<UserSummary[]>([]);
  const [names, setNames] = useState<Record<string, string>>({});

  useEffect(() => {
    if (!open) return;
    startTransition(async () => {
      const grants = await loadGrants(project.id, folder.id);
      setTargetPermission(grants.find((g) => g.type === "ROLE")?.permission ?? "NONE");
      setUserGrants(grants.filter((g) => g.type === "USER"));
      setOtherGrants(grants.filter((g) => g.type === "GROUP")); // kept as-is; groups are managed via the API
      const users = await searchUsers("", project.targetOrganizationId);
      setCandidates(users);
      setNames(Object.fromEntries(users.map((u) => [u.id, `${u.displayName} <${u.email}>`])));
    });
  }, [open, project.id, project.targetOrganizationId, folder.id]);

  const permissionOptions = [
    { value: "VIEW", label: t("view") },
    { value: "UPLOAD", label: t("upload") },
  ];

  const save = () =>
    startTransition(async () => {
      const grants: Grant[] = [
        ...(targetPermission === "NONE"
          ? []
          : [{ type: "ROLE" as const, principalId: null, permission: targetPermission }]),
        ...userGrants,
        ...otherGrants,
      ];
      if (onSaved(await saveGrants(project.id, folder.id, grants))) onClose();
    });

  return (
    <Modal
      title={t("accessTitle", { folder: folder.name })}
      open={open}
      okText={common("save")}
      cancelText={common("cancel")}
      confirmLoading={pending}
      onOk={save}
      onCancel={onClose}
      width={640}
    >
      <Typography.Paragraph type="secondary">{t("accessHelp")}</Typography.Paragraph>
      <Typography.Text strong>{t("targetAccess")}</Typography.Text>
      <Select
        style={{ width: "100%", margin: "8px 0 16px" }}
        value={targetPermission}
        onChange={setTargetPermission}
        options={[{ value: "NONE", label: t("none") }, ...permissionOptions]}
      />
      <Typography.Text strong>{t("userGrants")}</Typography.Text>
      <Flex vertical gap={8} style={{ marginTop: 8 }}>
        {userGrants.map((grant, index) => (
          <Flex key={grant.principalId} gap={8} align="center">
            <Typography.Text style={{ flex: 1 }} ellipsis>
              {names[grant.principalId ?? ""] ?? grant.principalId}
            </Typography.Text>
            <Select
              size="small"
              style={{ width: 180 }}
              value={grant.permission}
              options={permissionOptions}
              onChange={(permission: Permission) =>
                setUserGrants((list) => list.map((g, i) => (i === index ? { ...g, permission } : g)))
              }
            />
            <Button
              size="small"
              danger
              icon={<DeleteOutlined />}
              onClick={() => setUserGrants((list) => list.filter((_, i) => i !== index))}
            />
          </Flex>
        ))}
        <Select
          placeholder={t("addUser")}
          value={null}
          showSearch={{ optionFilterProp: "label" }}
          options={candidates
            .filter((u) => !userGrants.some((g) => g.principalId === u.id))
            .map((u) => ({ value: u.id, label: `${u.displayName} <${u.email}>` }))}
          onChange={(userId: string) =>
            setUserGrants((list) => [...list, { type: "USER", principalId: userId, permission: "VIEW" }])
          }
        />
      </Flex>
    </Modal>
  );
}

function UploadArea({ projectId, folder }: Readonly<{ projectId: string; folder: Folder }>) {
  const t = useTranslations("dataRoom");
  const router = useRouter();
  const { message } = App.useApp();
  const [directory, setDirectory] = useState(false);

  const customRequest: UploadProps["customRequest"] = ({ file, onProgress, onSuccess, onError }) => {
    const upload = file as File;
    const body = new FormData();
    body.append("file", upload);
    if (upload.webkitRelativePath) {
      body.append("relativePath", upload.webkitRelativePath);
    }
    // XMLHttpRequest instead of fetch for upload progress events.
    const xhr = new XMLHttpRequest();
    xhr.open(
      "POST",
      `/api/projects/${encodeURIComponent(projectId)}/folders/${encodeURIComponent(folder.id)}/documents`,
    );
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) onProgress?.({ percent: (event.loaded / event.total) * 100 });
    };
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        const result = JSON.parse(xhr.responseText) as UploadResponse;
        if (result.unchanged) message.info(t("unchanged", { name: upload.name }));
        else if (result.duplicateOf.length > 0) message.warning(t("duplicate", { name: upload.name }));
        onSuccess?.(result);
        router.refresh();
      } else {
        const problem = (() => {
          try {
            return JSON.parse(xhr.responseText) as { detail?: string };
          } catch {
            return {};
          }
        })();
        message.error(
          problem.detail ? `${upload.name}: ${problem.detail}` : t("uploadFailed", { name: upload.name }),
        );
        onError?.(new Error(problem.detail ?? `HTTP ${xhr.status}`));
      }
    };
    xhr.onerror = () => {
      message.error(t("uploadFailed", { name: upload.name }));
      onError?.(new Error("network"));
    };
    xhr.send(body);
  };

  return (
    <Flex vertical gap={8} style={{ marginBottom: 16 }}>
      <Space>
        <Switch size="small" checked={directory} onChange={setDirectory} />
        <Typography.Text>{t("uploadFolder")}</Typography.Text>
      </Space>
      <Upload.Dragger
        multiple
        directory={directory}
        customRequest={customRequest}
        showUploadList={{ showRemoveIcon: false }}
      >
        <p className="ant-upload-drag-icon">
          <InboxOutlined />
        </p>
        <p className="ant-upload-text">{t("dropHint")}</p>
        <p className="ant-upload-hint">{t("dropSub")}</p>
      </Upload.Dragger>
    </Flex>
  );
}

function DocumentTable({
  documents,
  onOpen,
}: Readonly<{ documents: DataRoomDocument[]; onOpen: (document: DataRoomDocument) => void }>) {
  const t = useTranslations("dataRoom");
  const format = useFormatter();
  return (
    <Table<DataRoomDocument>
      rowKey="id"
      size="small"
      dataSource={documents}
      pagination={documents.length > 50 ? { pageSize: 50 } : false}
      scroll={{ x: 640 }}
      onRow={(document) => ({ onClick: () => onOpen(document), style: { cursor: "pointer" } })}
      columns={[
        { title: t("name"), dataIndex: "name", ellipsis: true },
        {
          title: t("status"),
          key: "status",
          width: 170,
          render: (_, d) => (
            <Tag color={STATUS_COLORS[d.scanStatus]}>{t(`statusValues.${d.scanStatus}`)}</Tag>
          ),
        },
        { title: t("size"), key: "size", width: 100, render: (_, d) => formatBytes(d.sizeBytes) },
        {
          title: t("updated"),
          key: "updated",
          width: 180,
          render: (_, d) =>
            format.dateTime(new Date(d.createdAt), { dateStyle: "medium", timeStyle: "short" }),
        },
      ]}
    />
  );
}

function VersionsDrawer({
  projectId,
  document,
  onClose,
}: Readonly<{ projectId: string; document: DataRoomDocument | null; onClose: () => void }>) {
  const t = useTranslations("dataRoom");
  const format = useFormatter();
  const [versions, setVersions] = useState<DocumentVersion[]>([]);
  const [pending, startTransition] = useTransition();

  useEffect(() => {
    if (!document) return;
    startTransition(async () => setVersions(await loadVersions(projectId, document.id)));
  }, [document, projectId]);

  return (
    <Drawer title={document?.name} open={document !== null} onClose={onClose} size="large" loading={pending}>
      <Timeline
        items={versions.map((v) => ({
          color: v.scanStatus === "CLEAN" ? "green" : v.scanStatus === "PENDING" ? "blue" : "red",
          children: (
            <Descriptions
              size="small"
              column={1}
              title={`${t("version")} ${v.versionNumber}`}
              items={[
                {
                  key: "status",
                  label: t("status"),
                  children: (
                    <Tag color={STATUS_COLORS[v.scanStatus]}>{t(`statusValues.${v.scanStatus}`)}</Tag>
                  ),
                },
                {
                  key: "uploaded",
                  label: t("updated"),
                  children: format.dateTime(new Date(v.uploadedAt), {
                    dateStyle: "medium",
                    timeStyle: "short",
                  }),
                },
                { key: "size", label: t("size"), children: formatBytes(v.sizeBytes) },
                { key: "type", label: t("detectedType"), children: v.detectedContentType ?? "—" },
                ...(v.scanDetail ? [{ key: "detail", label: t("scanDetail"), children: v.scanDetail }] : []),
                {
                  key: "sha",
                  label: t("sha256"),
                  children: (
                    <Tooltip title={v.sha256}>
                      <Typography.Text code>{v.sha256.slice(0, 16)}…</Typography.Text>
                    </Tooltip>
                  ),
                },
              ]}
            />
          ),
        }))}
      />
    </Drawer>
  );
}
