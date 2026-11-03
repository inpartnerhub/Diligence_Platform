"use client";

import { BookOutlined, ImportOutlined, SearchOutlined } from "@ant-design/icons";
import { App, Button, Card, Drawer, Empty, Flex, Input, Select, Space, Spin, Tag, Typography } from "antd";
import { useTranslations } from "next-intl";
import { useEffect, useState, useTransition } from "react";

import { WORKSTREAMS, type Workstream, type WorkstreamType } from "@/features/projects/types";

import { importFindingFromLibrary, listFindingLibraryTemplates } from "./actions";
import { RiskTag } from "./RiskTag";
import { levelFor, type Finding, type FindingLibraryTemplate, type RiskMatrix } from "./types";

interface FindingLibraryDrawerProps {
  open: boolean;
  onClose: () => void;
  projectId: string;
  workstreams: Workstream[];
  matrix: RiskMatrix;
  onImported?: (finding: Finding) => void;
}

export function FindingLibraryDrawer({
  open,
  onClose,
  projectId,
  workstreams,
  matrix,
  onImported,
}: Readonly<FindingLibraryDrawerProps>) {
  const t = useTranslations("findings");
  const tw = useTranslations("workstreams");
  const { message } = App.useApp();

  const [loading, setLoading] = useState(true);
  const [templates, setTemplates] = useState<FindingLibraryTemplate[]>([]);
  const [selectedWorkstream, setSelectedWorkstream] = useState<string | undefined>(undefined);
  const [searchQuery, setSearchQuery] = useState("");
  const [importingId, setImportingId] = useState<string | null>(null);
  const [, startTransition] = useTransition();

  useEffect(() => {
    if (!open) return undefined;
    let active = true;
    void listFindingLibraryTemplates({
      workstream: selectedWorkstream,
      q: searchQuery.trim() || undefined,
    }).then((res) => {
      if (!active) return;
      setLoading(false);
      if (!res.ok) {
        message.error(res.message || t("error"));
      } else if (res.data) {
        setTemplates(res.data);
      }
    });
    return () => {
      active = false;
    };
  }, [open, selectedWorkstream, searchQuery, message, t]);

  const handleImport = (template: FindingLibraryTemplate) => {
    // Find matching project workstream
    const matchingWorkstream = workstreams.find((w) => w.type === template.workstreamType) ?? workstreams[0];
    const targetWorkstreamId = matchingWorkstream?.id;

    setImportingId(template.id);
    startTransition(async () => {
      const res = await importFindingFromLibrary(projectId, template.id, targetWorkstreamId);
      setImportingId(null);
      if (!res.ok) {
        message.error(res.message || t("error"));
      } else if (res.data) {
        message.success(t("templateImported"));
        onImported?.(res.data);
        onClose();
      }
    });
  };

  return (
    <Drawer
      title={
        <Space>
          <BookOutlined />
          <span>{t("templateLibrary")}</span>
        </Space>
      }
      open={open}
      onClose={onClose}
      width={720}
      styles={{ body: { padding: "16px 24px" } }}
    >
      <Typography.Paragraph type="secondary" style={{ marginBottom: 16 }}>
        {t("templateLibraryHelp")}
      </Typography.Paragraph>

      <Flex gap={12} wrap style={{ marginBottom: 20 }}>
        <Select
          allowClear
          placeholder={t("filterTemplates")}
          style={{ width: 220 }}
          value={selectedWorkstream}
          onChange={(val) => {
            setSelectedWorkstream(val);
            setLoading(true);
          }}
          options={WORKSTREAMS.map((type) => ({
            value: type,
            label: tw(type),
          }))}
        />
        <Input.Search
          allowClear
          placeholder={t("searchTemplates")}
          style={{ flex: 1, minWidth: 200 }}
          enterButton={<SearchOutlined />}
          onSearch={(val) => {
            setSearchQuery(val);
            setLoading(true);
          }}
        />
      </Flex>

      {loading ? (
        <Flex justify="center" align="center" style={{ minHeight: 240 }}>
          <Spin size="large" />
        </Flex>
      ) : templates.length === 0 ? (
        <Empty description={t("noTemplatesFound")} style={{ marginTop: 40 }} />
      ) : (
        <Flex vertical gap={16}>
          {templates.map((tmpl) => {
            const score = tmpl.defaultImpact * tmpl.defaultLikelihood;
            const level = levelFor(score, matrix);
            const isImporting = importingId === tmpl.id;

            return (
              <Card
                key={tmpl.id}
                size="small"
                hoverable
                styles={{ body: { padding: 16 } }}
                actions={[
                  <Button
                    key="use"
                    type="primary"
                    icon={<ImportOutlined />}
                    loading={isImporting}
                    onClick={() => handleImport(tmpl)}
                  >
                    {t("useTemplate")}
                  </Button>,
                ]}
              >
                <Flex vertical gap={8}>
                  <Flex justify="space-between" align="start" gap={8}>
                    <Flex vertical gap={4} style={{ flex: 1 }}>
                      <Typography.Text strong style={{ fontSize: 16 }}>
                        {tmpl.title}
                      </Typography.Text>
                      <Space size={4} wrap>
                        <Tag color="blue">{tw(tmpl.workstreamType as WorkstreamType)}</Tag>
                        <Tag>{tmpl.category}</Tag>
                        <RiskTag level={level} score={score} />
                      </Space>
                    </Flex>
                  </Flex>

                  <Typography.Paragraph
                    type="secondary"
                    style={{ margin: 0, fontSize: 13, whiteSpace: "pre-wrap" }}
                  >
                    {tmpl.standardDescription}
                  </Typography.Paragraph>

                  {tmpl.defaultRecommendation && (
                    <Card
                      size="small"
                      style={{ background: "rgba(0,0,0,0.02)", borderLeft: "3px solid #1677ff" }}
                    >
                      <Typography.Text strong style={{ fontSize: 12 }}>
                        {t("recommendation")}:{" "}
                      </Typography.Text>
                      <Typography.Text style={{ fontSize: 12, whiteSpace: "pre-wrap" }}>
                        {tmpl.defaultRecommendation}
                      </Typography.Text>
                    </Card>
                  )}
                </Flex>
              </Card>
            );
          })}
        </Flex>
      )}
    </Drawer>
  );
}
