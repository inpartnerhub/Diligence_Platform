import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { getTranslations } from "next-intl/server";

import { projectsApi } from "@/features/projects/api";
import type { Member } from "@/features/projects/types";
import { requestsApi } from "@/features/requests/api";
import { RequestListView } from "@/features/requests/RequestListView";
import { requestPermissions } from "@/features/requests/types";
import { ApiError } from "@/lib/api/client";
import { requireAccess } from "@/lib/auth/session";

type Params = Promise<{ id: string }>;
type SearchParams = Promise<Record<string, string | undefined>>;

/** Due dates are calendar days in the platform's time zone (WIB), like the backend reminders. */
const TODAY = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Jakarta" });

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("requests"))("title") };
}

/** Request list of a project (PRD Bab 11.1, REQ-01…REQ-06). */
export default async function ProjectRequestsPage({
  params,
  searchParams,
}: Readonly<{ params: Params; searchParams: SearchParams }>) {
  await requireAccess("requests");
  const { id } = await params;
  const { status, workstreamId, wave, q, item } = await searchParams;
  let project, items, workstreams;
  try {
    [project, items, workstreams] = await Promise.all([
      projectsApi.get(id),
      requestsApi.list(id, { status, workstreamId, wave, q }),
      projectsApi.workstreams(id),
    ]);
  } catch (error) {
    if (error instanceof ApiError && [400, 403, 404].includes(error.problem.status)) notFound();
    throw error;
  }
  const permissions = requestPermissions(project.myRoles, project.status === "ACTIVE");
  // Target-side members are the possible assignees; only the internal team can read the roster.
  let targetMembers: Member[] = [];
  if (permissions.edit) {
    targetMembers = (await projectsApi.members(id).catch(() => [])).filter((m) => m.role === "TARGET");
  }
  return (
    <RequestListView
      project={project}
      items={items}
      workstreams={workstreams}
      targetMembers={targetMembers}
      permissions={permissions}
      filters={{ status, workstreamId, wave, q }}
      selectedItemId={item ?? null}
      today={TODAY.format(new Date())}
    />
  );
}
