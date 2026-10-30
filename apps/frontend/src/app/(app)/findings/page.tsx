import { FindingIndexView } from "@/features/findings/FindingIndexView";
import { projectsApi } from "@/features/projects/api";
import { requireAccess } from "@/lib/auth/session";

/** Entry point from the navigation: pick the project whose findings to open. */
export default async function FindingsPage() {
  await requireAccess("findings");
  const projects = await projectsApi.list({ status: "ACTIVE", size: 100 });
  return <FindingIndexView projects={projects.items} />;
}
