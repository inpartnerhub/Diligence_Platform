import type { Metadata } from "next";
import { notFound } from "next/navigation";

import { projectsApi } from "@/features/projects/api";
import { ProjectDetailView } from "@/features/projects/ProjectDetailView";
import { ApiError } from "@/lib/api/client";
import { requireAccess } from "@/lib/auth/session";

type Params = Promise<{ id: string }>;

async function loadProject(id: string) {
  try {
    return await projectsApi.get(id);
  } catch (error) {
    // Projects outside the caller's memberships are invisible (row-level security) and surface as 404.
    if (error instanceof ApiError && (error.problem.status === 404 || error.problem.status === 400)) {
      notFound();
    }
    throw error;
  }
}

export async function generateMetadata({ params }: Readonly<{ params: Params }>): Promise<Metadata> {
  const project = await loadProject((await params).id);
  return { title: `${project.code} · ${project.name}` };
}

export default async function ProjectPage({ params }: Readonly<{ params: Params }>) {
  await requireAccess("projects");
  const { id } = await params;
  const project = await loadProject(id);
  const [workstreams, members, dashboard] = await Promise.all([
    projectsApi.workstreams(id),
    projectsApi.members(id),
    projectsApi.dashboard(id),
  ]);
  return (
    <ProjectDetailView project={project} workstreams={workstreams} members={members} dashboard={dashboard} />
  );
}
