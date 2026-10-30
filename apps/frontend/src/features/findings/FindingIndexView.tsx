"use client";

import { Card, Empty, List } from "antd";
import Link from "next/link";
import { useTranslations } from "next-intl";

import { PageHeader } from "@/components/PageHeader";
import type { Project } from "@/features/projects/types";

export function FindingIndexView({ projects }: Readonly<{ projects: Project[] }>) {
  const t = useTranslations("findings");
  return (
    <>
      <PageHeader title={t("pageTitle")} />
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
                  <Link key="open" href={`/projects/${p.id}/findings`}>
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
