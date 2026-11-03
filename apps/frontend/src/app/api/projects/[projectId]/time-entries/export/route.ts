import type { NextRequest } from "next/server";

import { proxyGet, segment } from "@/lib/api/proxy";

export const dynamic = "force-dynamic";

type Params = Promise<{ projectId: string }>;

/** Export project timesheet as CSV (PRD PRJ-06). */
export async function GET(_request: NextRequest, { params }: Readonly<{ params: Params }>) {
  const { projectId } = await params;
  return proxyGet(`/api/v1/projects/${segment(projectId)}/time-entries/export`);
}
