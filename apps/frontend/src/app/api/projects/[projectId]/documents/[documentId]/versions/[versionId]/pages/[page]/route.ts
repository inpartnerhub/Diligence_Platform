import { proxyGet, segment } from "@/lib/api/proxy";

export const dynamic = "force-dynamic";

type Params = Promise<{ projectId: string; documentId: string; versionId: string; page: string }>;

/** One watermarked preview page (PNG) for the signed-in viewer. */
export async function GET(_request: Request, { params }: Readonly<{ params: Params }>) {
  const { projectId, documentId, versionId, page } = await params;
  return proxyGet(
    `/api/v1/projects/${segment(projectId)}/documents/${segment(documentId)}/versions/${segment(versionId)}/pages/${segment(page)}`,
  );
}
