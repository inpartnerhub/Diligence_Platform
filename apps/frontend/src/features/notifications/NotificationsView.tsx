"use client";

import { App, Button, Card, Col, Empty, Pagination, Row, Segmented, Switch, Table } from "antd";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import { useState, useTransition } from "react";

import { PageHeader } from "@/components/PageHeader";
import type { Page } from "@/features/admin/types";
import { useUrlQuery } from "@/lib/navigation/useUrlQuery";

import { markAllRead, savePreferences } from "./actions";
import { NotificationList } from "./NotificationBell";
import type { InboxEntry, NotificationPreference } from "./types";

/** Inbox and per-type channel preferences (PRD ADM-05). */
export function NotificationsView({
  inbox,
  unreadOnly,
  preferences: initial,
}: Readonly<{ inbox: Page<InboxEntry>; unreadOnly: boolean; preferences: NotificationPreference[] }>) {
  const t = useTranslations("notifications");
  const router = useRouter();
  const { update } = useUrlQuery();
  const { message } = App.useApp();
  const [preferences, setPreferences] = useState(initial);
  const [pending, startTransition] = useTransition();

  const change = (type: NotificationPreference["type"], changes: Partial<NotificationPreference>) =>
    startTransition(async () => {
      const next = preferences.map((p) => (p.type === type ? { ...p, ...changes } : p));
      const result = await savePreferences(next.filter((p) => p.type === type));
      if (!result.ok) {
        message.error(t("error"));
        return;
      }
      setPreferences(result.data);
      message.success(t("saved"));
    });

  return (
    <>
      <PageHeader title={t("title")} />
      <Row gutter={[16, 16]}>
        <Col xs={24} xl={14}>
          <Card
            title={
              <Segmented
                value={unreadOnly ? "unread" : "all"}
                options={[
                  { value: "all", label: t("all") },
                  { value: "unread", label: t("unread") },
                ]}
                onChange={(value) => update({ unread: value === "unread" ? "true" : undefined })}
              />
            }
            extra={
              <Button
                onClick={() =>
                  startTransition(async () => {
                    await markAllRead();
                    router.refresh();
                  })
                }
                loading={pending}
              >
                {t("markAllRead")}
              </Button>
            }
          >
            {inbox.items.length === 0 ? (
              <Empty description={t("empty")} />
            ) : (
              <NotificationList entries={inbox.items} onOpened={() => router.refresh()} />
            )}
            <Pagination
              style={{ marginTop: 16, textAlign: "right" }}
              hideOnSinglePage
              current={inbox.page + 1}
              pageSize={inbox.size}
              total={inbox.totalElements}
              onChange={(page) => update({ page: page - 1 }, false)}
            />
          </Card>
        </Col>
        <Col xs={24} xl={10}>
          <Card title={t("preferences")}>
            <Table<NotificationPreference>
              rowKey="type"
              size="small"
              pagination={false}
              dataSource={preferences}
              columns={[
                { title: t("type"), dataIndex: "type", render: (type: string) => t(`types.${type}`) },
                {
                  title: t("email"),
                  dataIndex: "emailEnabled",
                  width: 90,
                  render: (value: boolean, p) => (
                    <Switch
                      size="small"
                      checked={value}
                      disabled={pending}
                      onChange={(emailEnabled) => change(p.type, { emailEnabled })}
                      aria-label={`${t("email")}: ${t(`types.${p.type}`)}`}
                    />
                  ),
                },
                {
                  title: t("inApp"),
                  dataIndex: "inAppEnabled",
                  width: 90,
                  render: (value: boolean, p) => (
                    <Switch
                      size="small"
                      checked={value}
                      disabled={pending}
                      onChange={(inAppEnabled) => change(p.type, { inAppEnabled })}
                      aria-label={`${t("inApp")}: ${t(`types.${p.type}`)}`}
                    />
                  ),
                },
              ]}
            />
          </Card>
        </Col>
      </Row>
    </>
  );
}
