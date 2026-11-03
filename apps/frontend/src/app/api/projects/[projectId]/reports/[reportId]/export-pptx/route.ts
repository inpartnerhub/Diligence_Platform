import type { NextRequest } from "next/server";

import { proxyGet, segment } from "@/lib/api/proxy";

export const dynamic = "force-dynamic";

type Params = Promise<{ projectId: string; reportId: string }>;

/** Export findings and executive summaries to PowerPoint (PPTX) presentation (PRD RPT-04). */
export async function GET(_request: NextRequest, { params }: Readonly<{ params: Params }>) {
  const { projectId, reportId } = await params;
  return proxyGet(`/api/v1/projects/${segment(projectId)}/reports/${segment(reportId)}/export-pptx`);
}
