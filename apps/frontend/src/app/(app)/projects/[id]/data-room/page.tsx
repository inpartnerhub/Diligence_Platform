import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { getTranslations } from "next-intl/server";

import { dataRoomApi } from "@/features/dataroom/api";
import { DataRoomView } from "@/features/dataroom/DataRoomView";
import { buildTree } from "@/features/dataroom/types";
import { projectsApi } from "@/features/projects/api";
import { MANAGER_ROLES } from "@/features/projects/types";
import { ApiError } from "@/lib/api/client";
import { requireAccess } from "@/lib/auth/session";

type Params = Promise<{ id: string }>;
type SearchParams = Promise<Record<string, string | undefined>>;

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("dataRoom"))("title") };
}

/** Data room (PRD Bab 11.1): folder tree, document list with upload, versions. */
export default async function DataRoomPage({
  params,
  searchParams,
}: Readonly<{ params: Params; searchParams: SearchParams }>) {
  await requireAccess("dataRoom");
  const { id } = await params;
  const { folder: folderParam } = await searchParams;
  let project, folders;
  try {
    [project, folders] = await Promise.all([projectsApi.get(id), dataRoomApi.folders(id)]);
  } catch (error) {
    if (error instanceof ApiError && [400, 403, 404].includes(error.problem.status)) notFound();
    throw error;
  }
  const selected = folders.find((f) => f.id === folderParam) ?? buildTree(folders)[0]?.folder ?? null;
  const documents = selected ? await dataRoomApi.documents(id, selected.id) : [];
  return (
    <DataRoomView
      project={project}
      folders={folders}
      selected={selected}
      documents={documents}
      canManage={project.myRoles.some((role) => MANAGER_ROLES.includes(role))}
    />
  );
}
