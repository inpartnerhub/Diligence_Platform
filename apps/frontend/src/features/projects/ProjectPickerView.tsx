"use client";

import { Card, Empty, Listy } from "antd";
import Link from "next/link";
import { useTranslations } from "next-intl";

import { ListRow } from "@/components/ListRow";
import { PageHeader } from "@/components/PageHeader";

import type { Project } from "./types";

/** Entry point of a per-project feature from the navigation: pick the project to open. */
export function ProjectPickerView({
  projects,
  title,
  path,
}: Readonly<{ projects: Project[]; title: string; path: "qna" | "reports" }>) {
  const t = useTranslations("picker");
  return (
    <>
      <PageHeader title={title} />
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
                  <Link key="open" href={`/projects/${p.id}/${path}`}>
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
