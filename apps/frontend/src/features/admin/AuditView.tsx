"use client";

import { DownloadOutlined } from "@ant-design/icons";
import { Button, DatePicker, Flex, Input, Select, Table, Tag, Typography } from "antd";
import dayjs from "dayjs";
import { useFormatter, useTranslations } from "next-intl";

import { PageHeader } from "@/components/PageHeader";
import { useUrlQuery } from "@/lib/navigation/useUrlQuery";

import type { AuditLog, Page } from "./types";

export function AuditView({ logs }: Readonly<{ logs: Page<AuditLog> }>) {
  const t = useTranslations();
  const format = useFormatter();
  const { params, update } = useUrlQuery();

  const from = params.get("from");
  const to = params.get("to");
  const dateRangeValue: [dayjs.Dayjs, dayjs.Dayjs] | null =
    from && to && dayjs(from).isValid() && dayjs(to).isValid()
      ? [dayjs(from), dayjs(to)]
      : null;

  const exportQuery = new URLSearchParams();
  if (params.get("action")) exportQuery.set("action", params.get("action")!);
  if (params.get("objectType")) exportQuery.set("objectType", params.get("objectType")!);
  if (params.get("from")) exportQuery.set("from", params.get("from")!);
  if (params.get("to")) exportQuery.set("to", params.get("to")!);
  if (params.get("userId")) exportQuery.set("userId", params.get("userId")!);
  const qs = exportQuery.toString();
  const exportUrl = `/api/admin/audit-logs/export${qs ? `?${qs}` : ""}`;

  return (
    <>
      <PageHeader
        title={t("audit.title")}
        extra={
          <Button icon={<DownloadOutlined />} href={exportUrl}>
            {t("audit.export")}
          </Button>
        }
      />
      <Flex gap={12} wrap style={{ marginBottom: 16 }}>
        <Input.Search
          allowClear
          defaultValue={params.get("action") ?? ""}
          placeholder={t("audit.filterAction")}
          style={{ width: 220 }}
          onSearch={(action) => update({ action })}
        />
        <Select
          allowClear
          placeholder={t("audit.filterObject")}
          value={params.get("objectType") ?? undefined}
          style={{ width: 160 }}
          options={["user", "organization", "project", "document", "finding", "qna", "report", "audit_log"].map((v) => ({
            value: v,
            label: v,
          }))}
          onChange={(objectType) => update({ objectType })}
        />
        <DatePicker.RangePicker
          showTime
          value={dateRangeValue}
          onChange={(dates) => {
            if (dates && dates[0] && dates[1]) {
              update({ from: dates[0].toISOString(), to: dates[1].toISOString() });
            } else {
              update({ from: undefined, to: undefined });
            }
          }}
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
