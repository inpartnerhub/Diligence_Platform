import type { NextRequest } from "next/server";

import { proxyGet, segment } from "@/lib/api/proxy";

export const dynamic = "force-dynamic";

type Params = Promise<{ projectId: string }>;

/** Export post-closing remediation schedule as CSV (PRD FND-05). */
export async function GET(_request: NextRequest, { params }: Readonly<{ params: Params }>) {
  const { projectId } = await params;
  return proxyGet(`/api/v1/projects/${segment(projectId)}/findings/remediation/export`);
}
