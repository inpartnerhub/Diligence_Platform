import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { getTranslations } from "next-intl/server";

import { projectsApi } from "@/features/projects/api";
import type { Member } from "@/features/projects/types";
import { qnaApi } from "@/features/qna/api";
import { QnaView } from "@/features/qna/QnaView";
import { qnaPermissions } from "@/features/qna/types";
import { ApiError } from "@/lib/api/client";
import { requireAccess } from "@/lib/auth/session";

type Params = Promise<{ id: string }>;
type SearchParams = Promise<Record<string, string | undefined>>;

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("qna"))("title") };
}

/** Q&A of a project (PRD Bab 11.1, QNA-01…QNA-03). */
export default async function ProjectQnaPage({
  params,
  searchParams,
}: Readonly<{ params: Params; searchParams: SearchParams }>) {
  await requireAccess("qna");
  const { id } = await params;
  const { status, q, question, request, finding, document } = await searchParams;
  let project, questions, workstreams;
  try {
    [project, questions, workstreams] = await Promise.all([
      projectsApi.get(id),
      qnaApi.list(id, { status, q }),
      projectsApi.workstreams(id),
    ]);
  } catch (error) {
    if (error instanceof ApiError && [400, 403, 404].includes(error.problem.status)) notFound();
    throw error;
  }
  const permissions = qnaPermissions(project.myRoles, project.status === "ACTIVE");
  let targetMembers: Member[] = [];
  if (permissions.edit) {
    targetMembers = (await projectsApi.members(id).catch(() => [])).filter((m) => m.role === "TARGET");
  }
  const source =
    request || finding || document
      ? { requestItemId: request, findingId: finding, documentId: document }
      : null;
  return (
    <QnaView
      project={project}
      questions={questions}
      workstreams={workstreams}
      targetMembers={targetMembers}
      permissions={permissions}
      filters={{ status, q }}
      selectedId={question ?? null}
      source={source}
    />
  );
}
