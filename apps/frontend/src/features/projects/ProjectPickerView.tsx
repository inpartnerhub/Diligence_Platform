"use client";

import { Card, Empty, List } from "antd";
import Link from "next/link";
import { useTranslations } from "next-intl";

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
                  <Link key="open" href={`/projects/${p.id}/${path}`}>
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
