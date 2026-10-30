import { projectsApi } from "@/features/projects/api";
import { RequestIndexView } from "@/features/requests/RequestIndexView";
import { requireAccess } from "@/lib/auth/session";

/** Entry point from the navigation: pick the project whose request list to open. */
export default async function RequestsPage() {
  await requireAccess("requests");
  const projects = await projectsApi.list({ status: "ACTIVE", size: 100 });
  return <RequestIndexView projects={projects.items} />;
}
