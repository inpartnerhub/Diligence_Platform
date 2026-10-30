"use client";

import { BellOutlined } from "@ant-design/icons";
import { Badge, Button, Empty, Flex, List, Popover, Spin, Typography } from "antd";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useFormatter, useTranslations } from "next-intl";
import { useCallback, useEffect, useState } from "react";

import { loadInbox, markAllRead, markRead, unreadCount } from "./actions";
import { messageKey, safeLink, type InboxEntry } from "./types";

const POLL_MS = 60_000;

/** Header bell (PRD ADM-05): unread count, polled every minute and on navigation, with the latest notifications. */
export function NotificationBell() {
  const t = useTranslations("notifications");
  const pathname = usePathname();
  const [unread, setUnread] = useState(0);
  const [open, setOpen] = useState(false);
  const [entries, setEntries] = useState<InboxEntry[] | null>(null);

  const refresh = useCallback(() => {
    void unreadCount().then(setUnread);
  }, []);

  useEffect(() => {
    refresh();
    const timer = setInterval(refresh, POLL_MS);
    return () => clearInterval(timer);
  }, [refresh, pathname]);

  const show = (next: boolean) => {
    setOpen(next);
    if (next) {
      setEntries(null);
      void loadInbox(0, false, 8)
        .then((page) => setEntries(page.items))
        .catch(() => setEntries([]));
    }
  };

  return (
    <Popover
      open={open}
      onOpenChange={show}
      trigger="click"
      placement="bottomRight"
      title={
        <Flex justify="space-between" align="center" gap={16}>
          {t("title")}
          {unread > 0 && (
            <Button
              size="small"
              type="link"
              onClick={() =>
                void markAllRead().then(() => {
                  setUnread(0);
                  setEntries(
                    (list) =>
                      list?.map((e) => ({ ...e, readAt: e.readAt ?? new Date().toISOString() })) ?? null,
                  );
                })
              }
            >
              {t("markAllRead")}
            </Button>
          )}
        </Flex>
      }
      content={
        <div style={{ width: 360, maxWidth: "80vw" }}>
          {entries === null ? (
            <Flex justify="center" style={{ padding: 24 }}>
              <Spin />
            </Flex>
          ) : entries.length === 0 ? (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t("empty")} />
          ) : (
            <NotificationList
              entries={entries}
              onOpened={() => {
                setOpen(false);
                refresh();
              }}
            />
          )}
          <Flex justify="center" style={{ marginTop: 8 }}>
            <Link href="/notifications" onClick={() => setOpen(false)}>
              {t("viewAll")}
            </Link>
          </Flex>
        </div>
      }
    >
      <Badge count={unread} size="small" overflowCount={99}>
        <Button
          type="text"
          shape="circle"
          icon={<BellOutlined />}
          aria-label={t("bellLabel", { count: unread })}
        />
      </Badge>
    </Popover>
  );
}

export function NotificationList({
  entries,
  onOpened,
}: Readonly<{ entries: InboxEntry[]; onOpened?: () => void }>) {
  const t = useTranslations("notifications");
  const format = useFormatter();
  const router = useRouter();

  const open = async (entry: InboxEntry) => {
    if (!entry.readAt) await markRead(entry.id).catch(() => undefined);
    onOpened?.();
    const link = safeLink(entry.link);
    if (link) router.push(link);
    else router.refresh();
  };

  return (
    <List
      size="small"
      dataSource={entries}
      renderItem={(entry) => (
        <List.Item
          onClick={() => void open(entry)}
          style={{ cursor: "pointer", background: entry.readAt ? undefined : "rgba(22, 119, 255, 0.06)" }}
        >
          <List.Item.Meta
            title={
              <Typography.Text strong={!entry.readAt}>
                {t(`messages.${messageKey(entry)}`, entry.params)}
              </Typography.Text>
            }
            description={format.relativeTime(new Date(entry.createdAt))}
          />
        </List.Item>
      )}
    />
  );
}
