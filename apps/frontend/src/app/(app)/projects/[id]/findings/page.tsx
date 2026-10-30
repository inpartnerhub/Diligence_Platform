import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { getTranslations } from "next-intl/server";

import { findingsApi } from "@/features/findings/api";
import { FindingListView } from "@/features/findings/FindingListView";
import { findingPermissions } from "@/features/findings/types";
import { projectsApi } from "@/features/projects/api";
import { ApiError } from "@/lib/api/client";
import { requireAccess } from "@/lib/auth/session";

type Params = Promise<{ id: string }>;
type SearchParams = Promise<Record<string, string | undefined>>;

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("findings"))("pageTitle") };
}

/** Risk register of a project (PRD Bab 11.1, FND-01…FND-04). */
export default async function ProjectFindingsPage({
  params,
  searchParams,
}: Readonly<{ params: Params; searchParams: SearchParams }>) {
  const session = await requireAccess("findings");
  const { id } = await params;
  const { status, workstreamId, level, q, finding, document, page } = await searchParams;
  let project, findings, workstreams, categories, matrix;
  try {
    [project, findings, workstreams, categories, matrix] = await Promise.all([
      projectsApi.get(id),
      findingsApi.list(id, { status, workstreamId, level, q }),
      projectsApi.workstreams(id),
      findingsApi.categories(id),
      findingsApi.matrix(),
    ]);
  } catch (error) {
    if (error instanceof ApiError && [400, 403, 404].includes(error.problem.status)) notFound();
    throw error;
  }
  return (
    <FindingListView
      project={project}
      findings={findings}
      workstreams={workstreams}
      categories={categories}
      matrix={matrix}
      permissions={findingPermissions(project.myRoles, project.status === "ACTIVE")}
      userId={session.user.id}
      filters={{ status, workstreamId, level, q }}
      selectedId={finding ?? null}
      source={document ? { documentId: document, page } : null}
    />
  );
}
