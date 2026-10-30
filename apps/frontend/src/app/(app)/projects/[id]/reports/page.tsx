import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { getTranslations } from "next-intl/server";

import { findingsApi } from "@/features/findings/api";
import { projectsApi } from "@/features/projects/api";
import { reportsApi } from "@/features/reports/api";
import { ReportsView } from "@/features/reports/ReportsView";
import { reportPermissions } from "@/features/reports/types";
import { ApiError } from "@/lib/api/client";
import { requireAccess } from "@/lib/auth/session";

type Params = Promise<{ id: string }>;

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("reports"))("title") };
}

/** Report versions of a project (PRD RPT-01). */
export default async function ProjectReportsPage({ params }: Readonly<{ params: Params }>) {
  await requireAccess("reports");
  const { id } = await params;
  let project, reports;
  try {
    [project, reports] = await Promise.all([projectsApi.get(id), reportsApi.list(id)]);
  } catch (error) {
    if (error instanceof ApiError && [400, 403, 404].includes(error.problem.status)) notFound();
    throw error;
  }
  const permissions = reportPermissions(project.myRoles, project.status === "ACTIVE");
  const released = permissions.word
    ? ((await findingsApi.list(id, { status: "RELEASED" }).catch(() => null))?.length ?? null)
    : null;
  return (
    <ReportsView project={project} reports={reports} permissions={permissions} releasedFindings={released} />
  );
}
