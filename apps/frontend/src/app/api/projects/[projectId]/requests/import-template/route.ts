import { proxyGet, segment } from "@/lib/api/proxy";

export const dynamic = "force-dynamic";

type Params = Promise<{ projectId: string }>;

/** Empty Excel sheet for importing request items (PRD REQ-01). */
export async function GET(_request: Request, { params }: Readonly<{ params: Params }>) {
  const { projectId } = await params;
  return proxyGet(`/api/v1/projects/${segment(projectId)}/requests/import-template`);
}
