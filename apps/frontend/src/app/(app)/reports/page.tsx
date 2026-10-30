import { getTranslations } from "next-intl/server";

import { projectsApi } from "@/features/projects/api";
import { ProjectPickerView } from "@/features/projects/ProjectPickerView";
import { requireAccess } from "@/lib/auth/session";

/** Entry point from the navigation: pick the project whose reports to open. */
export default async function ReportsIndexPage() {
  await requireAccess("reports");
  const projects = await projectsApi.list({ status: "ACTIVE", size: 100 });
  return (
    <ProjectPickerView
      projects={projects.items}
      title={(await getTranslations("reports"))("title")}
      path="reports"
    />
  );
}
