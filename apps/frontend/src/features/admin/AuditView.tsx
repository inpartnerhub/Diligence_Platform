"use client";

import { Flex, Input, Select, Table, Tag, Typography } from "antd";
import { useFormatter, useTranslations } from "next-intl";

import { PageHeader } from "@/components/PageHeader";
import { useUrlQuery } from "@/lib/navigation/useUrlQuery";

import type { AuditLog, Page } from "./types";

export function AuditView({ logs }: Readonly<{ logs: Page<AuditLog> }>) {
  const t = useTranslations();
  const format = useFormatter();
  const { params, update } = useUrlQuery();

  return (
    <>
      <PageHeader title={t("audit.title")} />
      <Flex gap={12} wrap style={{ marginBottom: 16 }}>
        <Input.Search
          allowClear
          defaultValue={params.get("action") ?? ""}
          placeholder={t("audit.filterAction")}
          style={{ width: 240 }}
          onSearch={(action) => update({ action })}
        />
        <Select
          allowClear
          placeholder={t("audit.filterObject")}
          value={params.get("objectType") ?? undefined}
          style={{ width: 180 }}
          options={["user", "organization", "project", "document", "finding"].map((v) => ({
            value: v,
            label: v,
          }))}
          onChange={(objectType) => update({ objectType })}
        />
      </Flex>
      <Table<AuditLog>
        rowKey="id"
        size="small"
        dataSource={logs.items}
        scroll={{ x: 900 }}
        pagination={{
          current: logs.page + 1,
          pageSize: logs.size,
          total: logs.totalElements,
          showSizeChanger: false,
          onChange: (page) => update({ page: page - 1 }, false),
        }}
        columns={[
          {
            title: t("audit.time"),
            key: "time",
            render: (_, l) =>
              format.dateTime(new Date(l.occurredAt), { dateStyle: "short", timeStyle: "medium" }),
          },
          {
            title: t("audit.user"),
            key: "user",
            render: (_, l) => <Typography.Text code>{l.userId?.slice(0, 8) ?? "system"}</Typography.Text>,
          },
          { title: t("audit.action"), dataIndex: "action" },
          {
            title: t("audit.object"),
            key: "object",
            render: (_, l) => `${l.objectType}${l.objectId ? ` · ${l.objectId.slice(0, 8)}` : ""}`,
          },
          { title: t("audit.ip"), dataIndex: "ipAddress" },
          {
            title: t("audit.outcome"),
            key: "outcome",
            render: (_, l) => <Tag color={l.outcome === "SUCCESS" ? "green" : "red"}>{l.outcome}</Tag>,
          },
        ]}
      />
    </>
  );
}
