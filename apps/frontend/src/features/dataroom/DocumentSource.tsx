"use client";

import { LinkOutlined, UploadOutlined } from "@ant-design/icons";
import { Alert, App, Button, Flex, Select, Spin, TreeSelect, Typography, Upload } from "antd";
import type { UploadProps } from "antd";
import { useTranslations } from "next-intl";
import { useEffect, useMemo, useState } from "react";

import { loadFolderDocuments, loadFolders } from "./actions";
import { buildTree, type DataRoomDocument, type Folder, type UploadResponse } from "./types";

/**
 * Where an attached document comes from: a new file uploaded into a data-room folder the caller may write to, or a
 * scanned document already in a folder they can see. Used for request deliveries (REQ-03) and Q&A answers (QNA-01);
 * the backend re-checks folder access when the document is linked.
 */
export function DocumentSource({
  projectId,
  busy,
  onChoose,
}: Readonly<{ projectId: string; busy: boolean; onChoose: (documentId: string) => void }>) {
  const t = useTranslations("documentSource");
  const { message } = App.useApp();
  const [folders, setFolders] = useState<Folder[] | null>(null);
  const [folderId, setFolderId] = useState<string | undefined>();
  const [documents, setDocuments] = useState<DataRoomDocument[]>([]);
  const [documentId, setDocumentId] = useState<string | undefined>();
  const [uploading, setUploading] = useState(false);

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

  const customRequest: UploadProps["customRequest"] = ({ file, onSuccess, onError }) => {
    if (!folderId) return;
    const upload = file as File;
    const body = new FormData();
    body.append("file", upload);
    setUploading(true);
    void fetch(
      `/api/projects/${encodeURIComponent(projectId)}/folders/${encodeURIComponent(folderId)}/documents`,
      { method: "POST", body },
    )
      .then(async (response) => {
        const payload = (await response.json().catch(() => ({}))) as UploadResponse & { detail?: string };
        if (!response.ok) {
          message.error(
            payload.detail ? `${upload.name}: ${payload.detail}` : t("uploadFailed", { name: upload.name }),
          );
          onError?.(new Error(payload.detail ?? `HTTP ${response.status}`));
          return;
        }
        onSuccess?.(payload);
        onChoose(payload.document.id);
      })
      .finally(() => setUploading(false));
  };

  if (folders === null) return <Spin size="small" />;
  if (folders.length === 0) return <Alert type="warning" showIcon title={t("noFolders")} />;
  return (
    <Flex vertical gap={8}>
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
        <Upload
          customRequest={customRequest}
          showUploadList={false}
          disabled={!folder?.canUpload || busy || uploading}
        >
          <Button icon={<UploadOutlined />} disabled={!folder?.canUpload} loading={busy || uploading}>
            {t("uploadAndAttach")}
          </Button>
        </Upload>
        <Select
          showSearch={{ optionFilterProp: "label" }}
          placeholder={t("chooseExisting")}
          disabled={!folderId}
          value={documentId}
          onChange={setDocumentId}
          style={{ minWidth: 220, flex: 1 }}
          options={documents
            .filter((d) => d.scanStatus === "CLEAN")
            .map((d) => ({ value: d.id, label: d.name }))}
        />
        <Button
          icon={<LinkOutlined />}
          disabled={!documentId}
          loading={busy}
          onClick={() => {
            if (documentId) {
              onChoose(documentId);
              setDocumentId(undefined);
            }
          }}
        >
          {t("attach")}
        </Button>
      </Flex>
      {folder && !folder.canUpload && <Typography.Text type="secondary">{t("noUploadHere")}</Typography.Text>}
    </Flex>
  );
}
