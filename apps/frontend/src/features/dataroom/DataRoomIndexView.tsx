"use client";

import { Card, Empty, Listy } from "antd";
import Link from "next/link";
import { useTranslations } from "next-intl";

import { ListRow } from "@/components/ListRow";
import { PageHeader } from "@/components/PageHeader";
import type { Project } from "@/features/projects/types";

export function DataRoomIndexView({ projects }: Readonly<{ projects: Project[] }>) {
  const t = useTranslations("dataRoom");
  return (
    <>
      <PageHeader title={t("title")} />
      <Card title={projects.length > 0 ? t("chooseProject") : undefined}>
        {projects.length === 0 ? (
          <Empty description={t("noProjects")} />
        ) : (
          <Listy
            items={projects}
            rowKey="id"
            itemRender={(p) => (
              <ListRow
                title={`${p.code} · ${p.name}`}
                description={p.targetName ?? undefined}
                actions={[
                  <Link key="open" href={`/projects/${p.id}/data-room`}>
                    {t("open")}
                  </Link>,
                ]}
              />
            )}
          />
        )}
      </Card>
    </>
  );
}
