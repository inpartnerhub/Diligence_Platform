import type { NextRequest } from "next/server";

import { proxyGet, segment } from "@/lib/api/proxy";

export const dynamic = "force-dynamic";

type Params = Promise<{ projectId: string; reportId: string }>;

/** Report download (PRD RPT-01): Word/PDF for the team, the watermarked PDF for the client; logged by the backend. */
export async function GET(request: NextRequest, { params }: Readonly<{ params: Params }>) {
  const { projectId, reportId } = await params;
  const format = request.nextUrl.searchParams.get("format") === "docx" ? "docx" : "pdf";
  return proxyGet(
    `/api/v1/projects/${segment(projectId)}/reports/${segment(reportId)}/download`,
    `?format=${format}`,
  );
}
