import type { NextRequest } from "next/server";

import { proxyMultipart } from "@/lib/api/proxy";

export const dynamic = "force-dynamic";

type Params = Promise<{ projectId: string }>;

/** Excel import of request items (PRD REQ-01); `dryRun=false` stores the rows once every row is valid. */
export async function POST(request: NextRequest, { params }: Readonly<{ params: Params }>) {
  const { projectId } = await params;
  const dryRun = request.nextUrl.searchParams.get("dryRun") === "false" ? "false" : "true";
  return proxyMultipart(
    request,
    `/api/v1/projects/${encodeURIComponent(projectId)}/requests/import?dryRun=${dryRun}`,
  );
}
