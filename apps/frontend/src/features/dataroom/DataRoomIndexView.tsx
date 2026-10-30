"use client";

import { Card, Empty, List } from "antd";
import Link from "next/link";
import { useTranslations } from "next-intl";

import { PageHeader } from "@/components/PageHeader";
import type { Project } from "@/features/projects/types";

export function DataRoomIndexView({ projects }: Readonly<{ projects: Project[] }>) {
  const t = useTranslations("dataRoom");
  return (
    <>
      <PageHeader title={t("title")} />
      <Card>
        {projects.length === 0 ? (
          <Empty description={t("noProjects")} />
        ) : (
          <List
            header={t("chooseProject")}
            dataSource={projects}
            renderItem={(p) => (
              <List.Item
                actions={[
                  <Link key="open" href={`/projects/${p.id}/data-room`}>
                    {t("open")}
                  </Link>,
                ]}
              >
                <List.Item.Meta title={`${p.code} · ${p.name}`} description={p.targetName ?? undefined} />
              </List.Item>
            )}
          />
        )}
      </Card>
    </>
  );
}
