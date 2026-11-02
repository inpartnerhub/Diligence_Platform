"use client";

import {
  AlertOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  FolderOpenOutlined,
} from "@ant-design/icons";
import {
  Badge,
  Card,
  Col,
  Empty,
  Flex,
  Input,
  Progress,
  Row,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  Typography,
} from "antd";
import type { ColumnsType } from "antd/es/table";
import Link from "next/link";
import { useTranslations } from "next-intl";
import { useMemo, useState } from "react";

import { PageHeader } from "@/components/PageHeader";
import { riskColors } from "@/theme/themeConfig";

import {
  STAGES,
  WORKSTREAMS,
  type PortfolioDashboard,
  type PortfolioProjectSummary,
  type ProjectStage,
  type WorkstreamType,
} from "./types";

export interface PortfolioDashboardViewProps {
  portfolio: PortfolioDashboard;
}

export function PortfolioDashboardView({ portfolio }: Readonly<PortfolioDashboardViewProps>) {
  const t = useTranslations();
  const [search, setSearch] = useState("");
  const [selectedStage, setSelectedStage] = useState<ProjectStage | "ALL">("ALL");
  const [selectedWorkstream, setSelectedWorkstream] = useState<WorkstreamType | "ALL">("ALL");
  const [selectedClient, setSelectedClient] = useState<string>("ALL");

  const clients = useMemo(() => {
    const set = new Set<string>();
    portfolio.projects.forEach((p) => {
      if (p.clientName) set.add(p.clientName);
    });
    return Array.from(set).sort();
  }, [portfolio.projects]);

  const filteredProjects = useMemo(() => {
    return portfolio.projects.filter((p) => {
      if (selectedStage !== "ALL" && p.stage !== selectedStage) {
        return false;
      }
      if (selectedWorkstream !== "ALL" && !p.workstreams.includes(selectedWorkstream)) {
        return false;
      }
      if (selectedClient !== "ALL" && p.clientName !== selectedClient) {
        return false;
      }
      if (search.trim()) {
        const query = search.trim().toLowerCase();
        const matchesCode = p.code.toLowerCase().includes(query);
        const matchesName = p.name.toLowerCase().includes(query);
        const matchesClient = p.clientName?.toLowerCase().includes(query) ?? false;
        const matchesTarget = p.targetName?.toLowerCase().includes(query) ?? false;
        const matchesLead = p.leadName?.toLowerCase().includes(query) ?? false;
        if (!matchesCode && !matchesName && !matchesClient && !matchesTarget && !matchesLead) {
          return false;
        }
      }
      return true;
    });
  }, [portfolio.projects, search, selectedStage, selectedWorkstream, selectedClient]);

  const avgCompletion = useMemo(() => {
    if (portfolio.projects.length === 0) return 0;
    const sum = portfolio.projects.reduce((acc, p) => acc + p.completionPercent, 0);
    return Math.round(sum / portfolio.projects.length);
  }, [portfolio.projects]);

  const columns: ColumnsType<PortfolioProjectSummary> = [
    {
      title: t("portfolio.project"),
      key: "project",
      render: (_, record) => (
        <Flex vertical gap={2}>
          <Link href={`/projects/${record.projectId}`}>
            <Typography.Text strong style={{ color: "#1890ff" }}>
              {record.name}
            </Typography.Text>
          </Link>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {record.code}
          </Typography.Text>
        </Flex>
      ),
    },
    {
      title: t("portfolio.clientTarget"),
      key: "clientTarget",
      render: (_, record) => (
        <Flex vertical gap={2}>
          <Typography.Text>{record.clientName ?? "—"}</Typography.Text>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            Target: {record.targetName ?? "—"}
          </Typography.Text>
        </Flex>
      ),
    },
    {
      title: t("portfolio.stage"),
      key: "stage",
      dataIndex: "stage",
      render: (stage: ProjectStage) => (
        <Tag color={stage === "CLOSED" ? "default" : "blue"}>{t(`stages.${stage}`)}</Tag>
      ),
    },
    {
      title: t("portfolio.progress"),
      key: "progress",
      render: (_, record) => (
        <div style={{ minWidth: 120 }}>
          <Progress percent={record.completionPercent} size="small" />
        </div>
      ),
    },
    {
      title: t("portfolio.findings"),
      key: "findings",
      render: (_, record) => (
        <Space size={8}>
          {record.criticalFindings > 0 && (
            <Tag color={riskColors.CRITICAL}>
              {record.criticalFindings} {t("risk.CRITICAL")}
            </Tag>
          )}
          {record.highFindings > 0 && (
            <Tag color={riskColors.HIGH}>
              {record.highFindings} {t("risk.HIGH")}
            </Tag>
          )}
          {record.criticalFindings === 0 && record.highFindings === 0 && (
            <Typography.Text type="secondary">0</Typography.Text>
          )}
        </Space>
      ),
    },
    {
      title: t("portfolio.deadlines"),
      key: "deadlines",
      render: (_, record) => (
        <Space size={8}>
          {record.overdueRequests > 0 ? (
            <Tag color="error">
              {record.overdueRequests} {t("project.overdue")}
            </Tag>
          ) : (
            <Tag color="success">0 {t("project.overdue")}</Tag>
          )}
          {record.dueSoonRequests > 0 && (
            <Tag color="warning">
              {record.dueSoonRequests} {t("project.dueSoon")}
            </Tag>
          )}
        </Space>
      ),
    },
    {
      title: t("portfolio.lead"),
      key: "lead",
      dataIndex: "leadName",
      render: (leadName: string | null) => (
        <Typography.Text type={leadName ? undefined : "secondary"}>{leadName ?? "—"}</Typography.Text>
      ),
    },
  ];

  return (
    <Flex vertical gap={24}>
      <Flex vertical gap={4} style={{ marginBottom: 8 }}>
        <PageHeader title={t("portfolio.title")} />
        <Typography.Text type="secondary">{t("portfolio.subtitle")}</Typography.Text>
      </Flex>

      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} lg={6}>
          <Card>
            <Statistic
              title={t("portfolio.activeProjects")}
              value={portfolio.activeProjects}
              suffix={`/ ${portfolio.totalProjects}`}
              prefix={<FolderOpenOutlined />}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card>
            <Statistic
              title={t("portfolio.avgFulfillment")}
              value={avgCompletion}
              suffix="%"
              prefix={<CheckCircleOutlined />}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card>
            <Statistic
              title={t("portfolio.overdueRequests")}
              value={portfolio.totalOverdueRequests}
              prefix={<ClockCircleOutlined />}
              styles={{
                content: portfolio.totalOverdueRequests > 0 ? { color: riskColors.CRITICAL } : undefined,
              }}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card>
            <Statistic
              title={t("portfolio.criticalFindings")}
              value={portfolio.criticalFindings}
              prefix={<AlertOutlined />}
              styles={{
                content: portfolio.criticalFindings > 0 ? { color: riskColors.CRITICAL } : undefined,
              }}
            />
          </Card>
        </Col>
      </Row>

      <Card title={t("portfolio.byStage")}>
        <Flex wrap="wrap" gap={12}>
          {STAGES.map((stage) => {
            const count = portfolio.projectsByStage[stage] ?? 0;
            return (
              <Badge key={stage} count={count} overflowCount={99} showZero color="#1890ff">
                <Tag
                  color={selectedStage === stage ? "blue" : "default"}
                  style={{
                    padding: "6px 12px",
                    cursor: "pointer",
                    fontSize: 13,
                    marginRight: 0,
                  }}
                  onClick={() => setSelectedStage(selectedStage === stage ? "ALL" : stage)}
                >
                  {t(`stages.${stage}`)}
                </Tag>
              </Badge>
            );
          })}
        </Flex>
      </Card>

      <Card>
        <Flex vertical gap={16}>
          <Row gutter={[16, 16]}>
            <Col xs={24} md={8}>
              <Input.Search
                allowClear
                placeholder={t("portfolio.filterSearch")}
                value={search}
                onChange={(e) => setSearch(e.target.value)}
              />
            </Col>
            <Col xs={12} sm={8} md={5}>
              <Select
                style={{ width: "100%" }}
                value={selectedStage}
                onChange={setSelectedStage}
                options={[
                  { value: "ALL", label: t("portfolio.allStages") },
                  ...STAGES.map((s) => ({ value: s, label: t(`stages.${s}`) })),
                ]}
              />
            </Col>
            <Col xs={12} sm={8} md={5}>
              <Select
                style={{ width: "100%" }}
                value={selectedWorkstream}
                onChange={setSelectedWorkstream}
                options={[
                  { value: "ALL", label: t("portfolio.allWorkstreams") },
                  ...WORKSTREAMS.map((w) => ({ value: w, label: t(`workstreams.${w}`) })),
                ]}
              />
            </Col>
            <Col xs={24} sm={8} md={6}>
              <Select
                style={{ width: "100%" }}
                value={selectedClient}
                onChange={setSelectedClient}
                options={[
                  { value: "ALL", label: t("portfolio.allClients") },
                  ...clients.map((c) => ({ value: c, label: c })),
                ]}
              />
            </Col>
          </Row>

          <Table
            rowKey="projectId"
            columns={columns}
            dataSource={filteredProjects}
            pagination={{ pageSize: 10, showSizeChanger: true }}
            locale={{
              emptyText: <Empty description={t("portfolio.noProjects")} />,
            }}
          />
        </Flex>
      </Card>
    </Flex>
  );
}
