import type { NextRequest } from "next/server";

import { proxyGet, segment } from "@/lib/api/proxy";

export const dynamic = "force-dynamic";

type Params = Promise<{ projectId: string; documentId: string }>;

/** Download (watermarked PDF or original), allowed and logged by the backend according to the folder policy. */
export async function GET(request: NextRequest, { params }: Readonly<{ params: Params }>) {
  const { projectId, documentId } = await params;
  const query = new URLSearchParams();
  for (const name of ["versionId", "format"]) {
    const value = request.nextUrl.searchParams.get(name);
    if (value) query.set(name, value);
  }
  return proxyGet(
    `/api/v1/projects/${segment(projectId)}/documents/${segment(documentId)}/download`,
    query.size > 0 ? `?${query}` : "",
  );
}
