import type { NextRequest } from "next/server";

import { proxyGet, segment } from "@/lib/api/proxy";

export const dynamic = "force-dynamic";

type Params = Promise<{ projectId: string; reportId: string }>;

/**
 * Report download (PRD RPT-01, RPT-04): Word/PDF/PowerPoint/Excel for the team, the watermarked PDF for the client;
 * logged by the backend.
 */
export async function GET(request: NextRequest, { params }: Readonly<{ params: Params }>) {
  const { projectId, reportId } = await params;
  const raw = request.nextUrl.searchParams.get("format");
  const format = raw === "docx" || raw === "pptx" || raw === "xlsx" ? raw : "pdf";
  return proxyGet(
    `/api/v1/projects/${segment(projectId)}/reports/${segment(reportId)}/download`,
    `?format=${format}`,
  );
}
