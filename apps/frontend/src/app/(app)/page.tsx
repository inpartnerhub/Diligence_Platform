import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

import { projectsApi } from "@/features/projects/api";
import { ProjectsView } from "@/features/projects/ProjectsView";
import { requireAccess } from "@/lib/auth/session";

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("projects"))("title") };
}

type SearchParams = Promise<Record<string, string | undefined>>;

/** Projects home (PRD Bab 11.1): projects the user can access; partners and PMs can create new ones. */
export default async function ProjectsHomePage({ searchParams }: Readonly<{ searchParams: SearchParams }>) {
  const session = await requireAccess("projects");
  const { page, q, status } = await searchParams;
  const canCreate = session.user.roles.some((role) => role === "PARTNER" || role === "PROJECT_MANAGER");
  const [projects, clients, targets, templates] = await Promise.all([
    projectsApi.list({ page, q, status, size: 20 }),
    canCreate ? projectsApi.organizations("CLIENT") : Promise.resolve([]),
    canCreate ? projectsApi.organizations("TARGET") : Promise.resolve([]),
    canCreate ? projectsApi.templates() : Promise.resolve([]),
  ]);
  return (
    <ProjectsView
      projects={projects}
      canCreate={canCreate}
      clients={clients}
      targets={targets}
      templates={templates}
    />
  );
}
