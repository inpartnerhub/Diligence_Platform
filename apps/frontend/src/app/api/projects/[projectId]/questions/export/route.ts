import type { NextRequest } from "next/server";

import { proxyGet, segment } from "@/lib/api/proxy";

export const dynamic = "force-dynamic";

type Params = Promise<{ projectId: string }>;

/** Q&A log as Excel or PDF (PRD QNA-03); the backend logs every export. */
export async function GET(request: NextRequest, { params }: Readonly<{ params: Params }>) {
  const { projectId } = await params;
  const format = request.nextUrl.searchParams.get("format") === "pdf" ? "pdf" : "xlsx";
  return proxyGet(`/api/v1/projects/${segment(projectId)}/questions/export`, `?format=${format}`);
}
