import { getTranslations } from "next-intl/server";

import { projectsApi } from "@/features/projects/api";
import { ProjectPickerView } from "@/features/projects/ProjectPickerView";
import { requireAccess } from "@/lib/auth/session";

/** Entry point from the navigation: pick the project whose Q&A to open. */
export default async function QnaIndexPage() {
  await requireAccess("qna");
  const projects = await projectsApi.list({ status: "ACTIVE", size: 100 });
  return (
    <ProjectPickerView projects={projects.items} title={(await getTranslations("qna"))("title")} path="qna" />
  );
}
