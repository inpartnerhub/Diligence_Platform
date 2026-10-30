import { DataRoomIndexView } from "@/features/dataroom/DataRoomIndexView";
import { projectsApi } from "@/features/projects/api";
import { requireAccess } from "@/lib/auth/session";

/** Entry point from the navigation: pick the project whose data room to open. */
export default async function DataRoomIndexPage() {
  await requireAccess("dataRoom");
  const projects = await projectsApi.list({ status: "ACTIVE", size: 100 });
  return <DataRoomIndexView projects={projects.items} />;
}
