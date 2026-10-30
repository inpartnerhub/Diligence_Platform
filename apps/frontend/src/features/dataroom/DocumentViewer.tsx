"use client";

import { DownloadOutlined, FilePdfOutlined, LockOutlined, PrinterOutlined } from "@ant-design/icons";
import { Alert, Button, Flex, Modal, Result, Space, Spin, Tag, Typography } from "antd";
import { useTranslations } from "next-intl";
import { useEffect, useRef, useState } from "react";

import { openPreview } from "./actions";
import type { PreviewInfo } from "./types";

export interface ViewerTarget {
  documentId: string;
  versionId?: string;
}

/*
 * Print control (PRD DOC-05): when printing is not allowed the whole page prints as a notice; when it is, only the
 * watermarked pages print. The PDF itself never reaches the browser unless it may be downloaded.
 */
const PRINT_CSS = `
@media print {
  body.dr-no-print * { display: none !important; }
  body.dr-no-print::after { content: attr(data-print-denied); display: block; padding: 48px; font: 16px sans-serif; }
  body.dr-print-viewer * { visibility: hidden; }
  body.dr-print-viewer .ant-modal-wrap, body.dr-print-viewer .ant-modal, body.dr-print-viewer .ant-modal-content,
  body.dr-print-viewer .ant-modal-body {
    position: static !important; overflow: visible !important; max-height: none !important; padding: 0 !important;
  }
  body.dr-print-viewer .dr-viewer-pages, body.dr-print-viewer .dr-viewer-pages * { visibility: visible; }
  body.dr-print-viewer .dr-viewer-pages { position: absolute; left: 0; top: 0; width: 100%; }
  .dr-viewer-pages img { break-after: page; width: 100% !important; box-shadow: none !important; }
}
`;

export function DocumentViewer({
  projectId,
  target,
  onClose,
}: Readonly<{ projectId: string; target: ViewerTarget | null; onClose: () => void }>) {
  const t = useTranslations("dataRoom.viewer");
  // The result is kept with the target it belongs to, so a new target shows the spinner until its own result.
  const [loaded, setLoaded] = useState<{
    target: ViewerTarget;
    info: PreviewInfo | null;
    error: string | null;
  } | null>(null);
  const [printing, setPrinting] = useState(false);
  const pagesRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!target) return;
    let active = true;
    void openPreview(projectId, target.documentId, target.versionId).then((result) => {
      if (!active) return;
      setLoaded(
        result.ok && result.data
          ? { target, info: result.data, error: null }
          : { target, info: null, error: result.ok ? "" : result.message },
      );
    });
    return () => {
      active = false;
    };
  }, [projectId, target]);

  const current = loaded && loaded.target === target ? loaded : null;
  const info = current?.info ?? null;
  const error = current?.error ?? null;
  const loading = target !== null && current === null;

  useEffect(() => {
    if (!target) return undefined;
    const className = info?.printAllowed ? "dr-print-viewer" : "dr-no-print";
    document.body.classList.add(className);
    document.body.dataset.printDenied = t("printDenied");
    return () => {
      document.body.classList.remove(className);
      delete document.body.dataset.printDenied;
    };
  }, [target, info?.printAllowed, t]);

  // Printing needs every page loaded, not just the ones scrolled into view.
  useEffect(() => {
    if (!printing) return;
    const images = Array.from(pagesRef.current?.querySelectorAll("img") ?? []);
    void Promise.allSettled(images.map((image) => image.decode())).then(() => {
      window.print();
      setPrinting(false);
    });
  }, [printing]);

  const ready = info?.previewStatus === "READY";
  const base = info
    ? `/api/projects/${encodeURIComponent(projectId)}/documents/${encodeURIComponent(info.documentId)}`
    : "";
  const download = (format: "PDF" | "ORIGINAL") =>
    `${base}/download?versionId=${encodeURIComponent(info?.versionId ?? "")}&format=${format}`;
  const protect = info && !info.downloadAllowed;

  return (
    <Modal
      open={target !== null}
      onCancel={onClose}
      footer={null}
      width={1000}
      style={{ top: 24 }}
      styles={{ body: { maxHeight: "calc(100vh - 140px)", overflowY: "auto" } }}
      title={
        info ? (
          <Flex gap={8} align="center" wrap>
            <Typography.Text strong ellipsis style={{ maxWidth: 420 }}>
              {info.name}
            </Typography.Text>
            <Tag>{t("version", { number: info.versionNumber })}</Tag>
            {ready && <Tag>{t("pages", { count: info.pageCount })}</Tag>}
          </Flex>
        ) : (
          t("title")
        )
      }
      destroyOnHidden
    >
      <style>{PRINT_CSS}</style>
      {loading && (
        <Flex justify="center" style={{ padding: 48 }}>
          <Spin />
        </Flex>
      )}
      {error !== null && <Result status="warning" title={error || t("error")} />}
      {info && (
        <Flex vertical gap={12}>
          <Flex gap={8} wrap justify="space-between" align="center">
            <Typography.Text type="secondary">{t("watermarkNotice")}</Typography.Text>
            <Space wrap>
              {info.downloadAllowed && info.pdfAvailable && (
                <Button icon={<FilePdfOutlined />} href={download("PDF")}>
                  {t("downloadPdf")}
                </Button>
              )}
              {info.downloadAllowed && info.originalAllowed && (
                <Button icon={<DownloadOutlined />} href={download("ORIGINAL")}>
                  {t("downloadOriginal")}
                </Button>
              )}
              {info.printAllowed && ready && (
                <Button icon={<PrinterOutlined />} loading={printing} onClick={() => setPrinting(true)}>
                  {t("print")}
                </Button>
              )}
              {!info.downloadAllowed && (
                <Tag icon={<LockOutlined />} color="default">
                  {t("downloadDenied")}
                </Tag>
              )}
            </Space>
          </Flex>
          {info.previewStatus === "PENDING" && (
            <Result status="info" title={t("preparing")} subTitle={t("preparingSub")} />
          )}
          {info.previewStatus === "UNAVAILABLE" && <Alert type="info" showIcon title={t("unavailable")} />}
          {info.previewStatus === "FAILED" && <Alert type="warning" showIcon title={t("failed")} />}
          {ready && (
            <div
              ref={pagesRef}
              className="dr-viewer-pages"
              style={{ background: "#f0f0f0", padding: 16, userSelect: protect ? "none" : undefined }}
              onContextMenu={protect ? (event) => event.preventDefault() : undefined}
            >
              {Array.from({ length: info.pageCount }, (_, index) => index + 1).map((page) => (
                // eslint-disable-next-line @next/next/no-img-element -- watermarked per request, never optimised or cached
                <img
                  key={page}
                  src={`${base}/versions/${encodeURIComponent(info.versionId)}/pages/${page}`}
                  alt={t("page", { number: page })}
                  loading={printing ? "eager" : "lazy"}
                  draggable={false}
                  style={{
                    display: "block",
                    width: "100%",
                    minHeight: 200,
                    margin: "0 auto 16px",
                    background: "#fff",
                    boxShadow: "0 1px 4px rgba(0,0,0,.15)",
                  }}
                />
              ))}
            </div>
          )}
        </Flex>
      )}
    </Modal>
  );
}
