import type { NextRequest } from "next/server";

import { proxyGet, segment } from "@/lib/api/proxy";

export const dynamic = "force-dynamic";

type Params = Promise<{ projectId: string; folderId: string }>;

/** Bulk download a folder as a watermarked ZIP archive (PRD DOC-05). */
export async function GET(request: NextRequest, { params }: Readonly<{ params: Params }>) {
  const { projectId, folderId } = await params;
  const query = new URLSearchParams();
  const format = request.nextUrl.searchParams.get("format");
  if (format) query.set("format", format);
  return proxyGet(
    `/api/v1/projects/${segment(projectId)}/folders/${segment(folderId)}/download`,
    query.size > 0 ? `?${query}` : "",
  );
}
