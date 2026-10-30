import type { NextRequest } from "next/server";

import { proxyMultipart, segment } from "@/lib/api/proxy";

export const dynamic = "force-dynamic";

type Params = Promise<{ projectId: string; folderId: string }>;

/** Upload into a data-room folder, streamed to the backend (PRD DOC-02). */
export async function POST(request: NextRequest, { params }: Readonly<{ params: Params }>) {
  const { projectId, folderId } = await params;
  return proxyMultipart(
    request,
    `/api/v1/projects/${segment(projectId)}/folders/${segment(folderId)}/documents`,
  );
}
