"use client";

import {
  AlertOutlined,
  DeleteOutlined,
  DownloadOutlined,
  FilePdfOutlined,
  FileSearchOutlined,
  HighlightOutlined,
  LockOutlined,
  PrinterOutlined,
  QuestionCircleOutlined,
  SyncOutlined,
} from "@ant-design/icons";
import {
  Alert,
  App,
  Button,
  Card,
  Drawer,
  Flex,
  Input,
  Modal,
  Popconfirm,
  Popover,
  Radio,
  Result,
  Space,
  Spin,
  Tag,
  Typography,
} from "antd";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import { useEffect, useRef, useState } from "react";

import {
  createAnnotation,
  deleteAnnotation,
  loadAnnotations,
  loadDocumentClauses,
  openPreview,
  triggerClauseExtraction,
} from "./actions";
import type { DocumentAnnotation, DocumentClause, PreviewInfo } from "./types";

/** Preview pages requested immediately when the viewer opens (DOC-03: preview ≤ 3 s). */
const EAGER_PAGES = 2;

export interface ViewerTarget {
  documentId: string;
  versionId?: string;
  initialPage?: number;
  highlightAnnotationId?: string;
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
  body.dr-print-viewer .dr-viewer-action { display: none !important; }
  body.dr-print-viewer .dr-viewer-pages { position: absolute; left: 0; top: 0; width: 100%; }
  .dr-viewer-pages img { break-after: page; width: 100% !important; box-shadow: none !important; }
}
`;

/**
 * @param findingFromPage  when set (internal team), each page offers annotations and "create finding from this page" (PRD FND-02, DOC-08)
 */
export function DocumentViewer({
  projectId,
  target,
  onClose,
  findingFromPage,
}: Readonly<{
  projectId: string;
  target: ViewerTarget | null;
  onClose: () => void;
  findingFromPage?: boolean;
}>) {
  const t = useTranslations("dataRoom.viewer");
  const router = useRouter();
  const { message } = App.useApp();

  // The result is kept with the target it belongs to, so a new target shows the spinner until its own result.
  const [loaded, setLoaded] = useState<{
    target: ViewerTarget;
    info: PreviewInfo | null;
    error: string | null;
  } | null>(null);
  const [printing, setPrinting] = useState(false);
  const pagesRef = useRef<HTMLDivElement>(null);

  // Annotations state (DOC-08)
  const [annotations, setAnnotations] = useState<DocumentAnnotation[]>([]);
  const [annotating, setAnnotating] = useState(false);
  const [userFocusedAnnotationId, setUserFocusedAnnotationId] = useState<string | null>(null);
  const focusedAnnotationId = userFocusedAnnotationId ?? target?.highlightAnnotationId ?? null;
  const [drawing, setDrawing] = useState<{
    pageNumber: number;
    startX: number;
    startY: number;
    currentX: number;
    currentY: number;
  } | null>(null);
  const [draftAnnotation, setDraftAnnotation] = useState<{
    pageNumber: number;
    xPct: number;
    yPct: number;
    widthPct: number;
    heightPct: number;
  } | null>(null);
  const [draftComment, setDraftComment] = useState("");
  const [draftColor, setDraftColor] = useState("#faad14");
  const [savingAnnotation, setSavingAnnotation] = useState(false);

  // Key clauses state (DOC-09)
  const [clauses, setClauses] = useState<DocumentClause[]>([]);
  const [clausesOpen, setClausesOpen] = useState(false);
  const [extractingClauses, setExtractingClauses] = useState(false);
  const [focusedClauseId, setFocusedClauseId] = useState<string | null>(null);

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

  // Load annotations for internal users (DOC-08)
  useEffect(() => {
    if (!target || !findingFromPage) return;
    let active = true;
    void loadAnnotations(projectId, target.documentId).then((result) => {
      if (active && result.ok && result.data) {
        setAnnotations(result.data);
      }
    });
    return () => {
      active = false;
    };
  }, [projectId, target, findingFromPage]);

  // Load key clauses for internal users (DOC-09)
  useEffect(() => {
    if (!target || !findingFromPage) return;
    let active = true;
    void loadDocumentClauses(projectId, target.documentId).then((result) => {
      if (active && result.ok && result.data) {
        setClauses(result.data);
      }
    });
    return () => {
      active = false;
    };
  }, [projectId, target, findingFromPage]);

  const handleReExtractClauses = async () => {
    if (!target) return;
    setExtractingClauses(true);
    try {
      const res = await triggerClauseExtraction(projectId, target.documentId);
      if (res.ok && res.data) {
        setClauses(res.data);
        message.success(t("reExtractSuccess"));
      } else {
        message.error((!res.ok && res.message) || t("error"));
      }
    } finally {
      setExtractingClauses(false);
    }
  };

  // Initial page scrolling
  useEffect(() => {
    if (!target?.initialPage) return;
    const initialPage = target.initialPage;
    const timer = setTimeout(() => {
      const el = document.getElementById(`dr-page-${initialPage}`);
      el?.scrollIntoView({ behavior: "smooth", block: "start" });
    }, 200);
    return () => clearTimeout(timer);
  }, [target?.initialPage, loaded]);

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

  const handlePageMouseDown = (page: number, e: React.MouseEvent<HTMLDivElement>) => {
    if (!annotating) return;
    const rect = e.currentTarget.getBoundingClientRect();
    const x = Math.max(0, Math.min(100, ((e.clientX - rect.left) / rect.width) * 100));
    const y = Math.max(0, Math.min(100, ((e.clientY - rect.top) / rect.height) * 100));
    setDrawing({ pageNumber: page, startX: x, startY: y, currentX: x, currentY: y });
  };

  const handlePageMouseMove = (page: number, e: React.MouseEvent<HTMLDivElement>) => {
    if (!drawing || drawing.pageNumber !== page) return;
    const rect = e.currentTarget.getBoundingClientRect();
    const x = Math.max(0, Math.min(100, ((e.clientX - rect.left) / rect.width) * 100));
    const y = Math.max(0, Math.min(100, ((e.clientY - rect.top) / rect.height) * 100));
    setDrawing((prev) => (prev ? { ...prev, currentX: x, currentY: y } : null));
  };

  const handlePageMouseUp = (page: number) => {
    if (!drawing || drawing.pageNumber !== page) return;
    const minX = Math.min(drawing.startX, drawing.currentX);
    const minY = Math.min(drawing.startY, drawing.currentY);
    const width = Math.abs(drawing.currentX - drawing.startX);
    const height = Math.abs(drawing.currentY - drawing.startY);
    setDrawing(null);
    if (width >= 1 && height >= 1) {
      setDraftAnnotation({ pageNumber: page, xPct: minX, yPct: minY, widthPct: width, heightPct: height });
      setDraftComment("");
      setDraftColor("#faad14");
    }
  };

  const handleSaveAnnotation = async (redirectToFinding = false) => {
    if (!draftAnnotation || !target) return;
    setSavingAnnotation(true);
    try {
      const res = await createAnnotation(projectId, target.documentId, {
        pageNumber: draftAnnotation.pageNumber,
        xPct: Number(draftAnnotation.xPct.toFixed(2)),
        yPct: Number(draftAnnotation.yPct.toFixed(2)),
        widthPct: Number(draftAnnotation.widthPct.toFixed(2)),
        heightPct: Number(draftAnnotation.heightPct.toFixed(2)),
        color: draftColor,
        comment: draftComment.trim() || undefined,
      });
      if (res.ok && res.data) {
        message.success(t("annotationSaved"));
        setAnnotations((prev) => [...prev, res.data!]);
        const createdId = res.data.id;
        const pageNum = draftAnnotation.pageNumber;
        setDraftAnnotation(null);
        if (redirectToFinding) {
          router.push(
            `/projects/${encodeURIComponent(projectId)}/findings?new=1&document=${encodeURIComponent(target.documentId)}&page=${encodeURIComponent(t("pageReference", { number: pageNum }))}&annotationId=${encodeURIComponent(createdId)}`,
          );
        }
      } else {
        message.error(!res.ok && res.message ? res.message : t("error"));
      }
    } finally {
      setSavingAnnotation(false);
    }
  };

  const handleDeleteAnnotation = async (annotationId: string) => {
    if (!target) return;
    const res = await deleteAnnotation(projectId, target.documentId, annotationId);
    if (res.ok) {
      message.success(t("annotationDeleted"));
      setAnnotations((prev) => prev.filter((a) => a.id !== annotationId));
      if (focusedAnnotationId === annotationId) setUserFocusedAnnotationId(null);
    } else {
      message.error(res.message || t("error"));
    }
  };

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
              {findingFromPage && ready && (
                <>
                  <Button
                    icon={<FileSearchOutlined />}
                    type={clausesOpen ? "primary" : "default"}
                    onClick={() => setClausesOpen((prev) => !prev)}
                  >
                    {t("clauses")} {clauses.length > 0 && `(${clauses.length})`}
                  </Button>
                  <Button
                    icon={<HighlightOutlined />}
                    type={annotating ? "primary" : "default"}
                    onClick={() => setAnnotating((prev) => !prev)}
                  >
                    {t("annotate")} {annotations.length > 0 && `(${annotations.length})`}
                  </Button>
                </>
              )}
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
              {findingFromPage && (
                <Link
                  href={`/projects/${encodeURIComponent(projectId)}/qna?new=1&document=${encodeURIComponent(info.documentId)}`}
                >
                  <Button icon={<QuestionCircleOutlined />}>{t("askQuestion")}</Button>
                </Link>
              )}
              {!info.downloadAllowed && (
                <Tag icon={<LockOutlined />} color="default">
                  {t("downloadDenied")}
                </Tag>
              )}
            </Space>
          </Flex>
          {annotating && (
            <Alert
              type="info"
              showIcon
              message={t("annotateMode")}
              description={t("annotateHint")}
              style={{ marginBottom: 4 }}
            />
          )}
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
                <div key={page} id={`dr-page-${page}`} style={{ marginBottom: 16 }}>
                  {findingFromPage && (
                    <Flex
                      justify="space-between"
                      align="center"
                      className="dr-viewer-action"
                      style={{ marginBottom: 4 }}
                    >
                      <Flex gap={6} wrap>
                        {clauses
                          .filter((c) => c.pageNumber === page)
                          .map((clause) => (
                            <Tag
                              key={clause.id}
                              color="blue"
                              icon={<FileSearchOutlined />}
                              style={{ cursor: "pointer" }}
                              onClick={() => {
                                setClausesOpen(true);
                                setFocusedClauseId(clause.id);
                              }}
                            >
                              {t(`clauseTypes.${clause.clauseType}`, {
                                defaultValue: clause.clauseType,
                              })}{" "}
                              · {Math.round(clause.confidence * 100)}%
                            </Tag>
                          ))}
                      </Flex>
                      <Link
                        href={`/projects/${encodeURIComponent(projectId)}/findings?new=1&document=${encodeURIComponent(info.documentId)}&page=${encodeURIComponent(t("pageReference", { number: page }))}`}
                      >
                        <AlertOutlined /> {t("findingFromPage", { number: page })}
                      </Link>
                    </Flex>
                  )}
                  <div
                    style={{ position: "relative", display: "inline-block", width: "100%" }}
                    onMouseDown={(e) => handlePageMouseDown(page, e)}
                    onMouseMove={(e) => handlePageMouseMove(page, e)}
                    onMouseUp={() => handlePageMouseUp(page)}
                  >
                    {/* eslint-disable-next-line @next/next/no-img-element -- watermarked per request, never optimised or cached */}
                    <img
                      src={`${base}/versions/${encodeURIComponent(info.versionId)}/pages/${page}`}
                      alt={t("page", { number: page })}
                      // The first pages load at once; later ones lazily (the backend prefetches ahead, #59).
                      loading={printing || page <= EAGER_PAGES ? "eager" : "lazy"}
                      fetchPriority={page === 1 ? "high" : "auto"}
                      decoding="async"
                      draggable={false}
                      style={{
                        display: "block",
                        width: "100%",
                        minHeight: 200,
                        margin: 0,
                        background: "#fff",
                        boxShadow: "0 1px 4px rgba(0,0,0,.15)",
                        cursor: annotating ? "crosshair" : "default",
                      }}
                    />

                    {/* Annotations overlay markers (DOC-08) */}
                    {findingFromPage &&
                      annotations
                        .filter((a) => a.pageNumber === page)
                        .map((ann) => {
                          const isFocused = focusedAnnotationId === ann.id;
                          return (
                            <Popover
                              key={ann.id}
                              title={
                                <Flex justify="space-between" align="center" gap={8}>
                                  <Typography.Text strong>
                                    {ann.createdByName || t("annotate")} · {t("page", { number: page })}
                                  </Typography.Text>
                                  {ann.findingId && (
                                    <Tag color="purple">
                                      <AlertOutlined /> {t("linkedFinding")}
                                    </Tag>
                                  )}
                                </Flex>
                              }
                              content={
                                <Flex vertical gap={8} style={{ maxWidth: 280 }}>
                                  {ann.comment && (
                                    <Typography.Paragraph style={{ margin: 0 }}>
                                      {ann.comment}
                                    </Typography.Paragraph>
                                  )}
                                  <Flex
                                    justify="space-between"
                                    align="center"
                                    gap={8}
                                    style={{ marginTop: 4 }}
                                  >
                                    <Link
                                      href={`/projects/${encodeURIComponent(projectId)}/findings?new=1&document=${encodeURIComponent(info.documentId)}&page=${encodeURIComponent(t("pageReference", { number: page }))}&annotationId=${encodeURIComponent(ann.id)}`}
                                    >
                                      <Button
                                        size="small"
                                        type="link"
                                        icon={<AlertOutlined />}
                                        style={{ padding: 0 }}
                                      >
                                        {t("createFindingFromAnnotation")}
                                      </Button>
                                    </Link>
                                    <Popconfirm
                                      title={t("deleteAnnotationConfirm")}
                                      onConfirm={() => handleDeleteAnnotation(ann.id)}
                                    >
                                      <Button size="small" danger type="text" icon={<DeleteOutlined />} />
                                    </Popconfirm>
                                  </Flex>
                                </Flex>
                              }
                            >
                              <div
                                onClick={() => setUserFocusedAnnotationId(ann.id)}
                                style={{
                                  position: "absolute",
                                  left: `${ann.xPct}%`,
                                  top: `${ann.yPct}%`,
                                  width: `${ann.widthPct}%`,
                                  height: `${ann.heightPct}%`,
                                  backgroundColor: ann.color ? `${ann.color}44` : "rgba(250, 219, 20, 0.35)",
                                  border: isFocused
                                    ? "2px solid #1890ff"
                                    : `1.5px solid ${ann.color || "#d4b106"}`,
                                  boxShadow: isFocused ? "0 0 0 4px rgba(24, 144, 255, 0.5)" : undefined,
                                  borderRadius: 3,
                                  cursor: "pointer",
                                  zIndex: isFocused ? 5 : 2,
                                  transition: "all 0.2s ease",
                                }}
                              />
                            </Popover>
                          );
                        })}

                    {/* Rubberband dragging preview */}
                    {drawing && drawing.pageNumber === page && (
                      <div
                        style={{
                          position: "absolute",
                          left: `${Math.min(drawing.startX, drawing.currentX)}%`,
                          top: `${Math.min(drawing.startY, drawing.currentY)}%`,
                          width: `${Math.abs(drawing.currentX - drawing.startX)}%`,
                          height: `${Math.abs(drawing.currentY - drawing.startY)}%`,
                          border: "2px dashed #1890ff",
                          backgroundColor: "rgba(24, 144, 255, 0.2)",
                          borderRadius: 3,
                          pointerEvents: "none",
                          zIndex: 10,
                        }}
                      />
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
        </Flex>
      )}

      {/* Draft annotation creation modal (DOC-08) */}
      <Modal
        open={draftAnnotation !== null}
        title={`${t("newAnnotation")} · ${t("page", { number: draftAnnotation?.pageNumber ?? 1 })}`}
        onCancel={() => setDraftAnnotation(null)}
        footer={[
          <Button key="cancel" onClick={() => setDraftAnnotation(null)}>
            {t("cancel")}
          </Button>,
          <Button
            key="finding"
            icon={<AlertOutlined />}
            loading={savingAnnotation}
            onClick={() => handleSaveAnnotation(true)}
          >
            {t("createFindingFromAnnotation")}
          </Button>,
          <Button
            key="save"
            type="primary"
            loading={savingAnnotation}
            onClick={() => handleSaveAnnotation(false)}
          >
            {t("saveAnnotation")}
          </Button>,
        ]}
      >
        <Flex vertical gap={12} style={{ paddingTop: 8 }}>
          <div>
            <Typography.Text type="secondary">{t("highlightColor")}</Typography.Text>
            <Radio.Group
              value={draftColor}
              onChange={(e) => setDraftColor(e.target.value)}
              style={{ marginTop: 6, display: "flex", gap: 8 }}
            >
              {[
                { label: t("colorYellow"), value: "#faad14" },
                { label: t("colorRed"), value: "#ff4d4f" },
                { label: t("colorGreen"), value: "#52c41a" },
                { label: t("colorBlue"), value: "#1890ff" },
              ].map((c) => (
                <Radio.Button key={c.value} value={c.value}>
                  <span
                    style={{
                      display: "inline-block",
                      width: 10,
                      height: 10,
                      borderRadius: "50%",
                      background: c.value,
                      marginRight: 6,
                      verticalAlign: "middle",
                    }}
                  />
                  {c.label}
                </Radio.Button>
              ))}
            </Radio.Group>
          </div>
          <div>
            <Typography.Text type="secondary">{t("annotationComment")}</Typography.Text>
            <Input.TextArea
              rows={3}
              placeholder={t("commentPlaceholder")}
              value={draftComment}
              onChange={(e) => setDraftComment(e.target.value)}
              style={{ marginTop: 6 }}
            />
          </div>
        </Flex>
      </Modal>

      {/* Key Clauses Inspector Drawer (DOC-09) */}
      <Drawer
        title={t("contractClauses")}
        placement="right"
        width={420}
        open={clausesOpen}
        onClose={() => setClausesOpen(false)}
        extra={
          <Button
            size="small"
            icon={<SyncOutlined spin={extractingClauses} />}
            loading={extractingClauses}
            onClick={handleReExtractClauses}
          >
            {t("reExtract")}
          </Button>
        }
      >
        {clauses.length === 0 ? (
          <Alert type="info" showIcon message={t("noClausesFound")} />
        ) : (
          <Flex vertical gap={12}>
            <Typography.Text type="secondary">{t("clauseFound", { count: clauses.length })}</Typography.Text>
            {clauses.map((clause) => {
              const isFocused = focusedClauseId === clause.id;
              return (
                <Card
                  key={clause.id}
                  size="small"
                  style={{
                    borderColor: isFocused ? "#1890ff" : undefined,
                    boxShadow: isFocused ? "0 0 6px rgba(24, 144, 255, 0.4)" : undefined,
                  }}
                  title={
                    <Flex justify="space-between" align="center" gap={8}>
                      <Tag color="cyan">
                        {t(`clauseTypes.${clause.clauseType}`, { defaultValue: clause.clauseType })}
                      </Tag>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        {t("confidenceScore", { score: Math.round(clause.confidence * 100) })}
                      </Typography.Text>
                    </Flex>
                  }
                  extra={
                    <Button
                      size="small"
                      type="link"
                      onClick={() => {
                        const el = document.getElementById(`dr-page-${clause.pageNumber}`);
                        el?.scrollIntoView({ behavior: "smooth", block: "start" });
                        setFocusedClauseId(clause.id);
                      }}
                    >
                      {t("jumpToPage", { page: clause.pageNumber })}
                    </Button>
                  }
                >
                  <Typography.Paragraph
                    ellipsis={{ rows: 4, expandable: true }}
                    style={{
                      margin: 0,
                      background: "#fafafa",
                      padding: 8,
                      borderRadius: 4,
                      fontSize: 13,
                      fontStyle: "italic",
                    }}
                  >
                    {`"${clause.extractedText}"`}
                  </Typography.Paragraph>
                  {info && (
                    <Flex justify="end" style={{ marginTop: 8 }}>
                      <Link
                        href={`/projects/${encodeURIComponent(projectId)}/findings?new=1&document=${encodeURIComponent(info.documentId)}&page=${encodeURIComponent(t("pageReference", { number: clause.pageNumber }))}&title=${encodeURIComponent(`Key Clause: ${t("clauseTypes." + clause.clauseType, { defaultValue: clause.clauseType })} in ${info.name}`)}&description=${encodeURIComponent(clause.extractedText)}`}
                      >
                        <Button size="small" type="primary" ghost icon={<AlertOutlined />}>
                          {t("createFindingFromClause")}
                        </Button>
                      </Link>
                    </Flex>
                  )}
                </Card>
              );
            })}
          </Flex>
        )}
      </Drawer>
    </Modal>
  );
}
