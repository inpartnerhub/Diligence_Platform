import type { NextRequest } from "next/server";

import { getSession } from "@/lib/auth/session";
import { serverEnv } from "@/lib/config/env";

export const dynamic = "force-dynamic";

type Params = Promise<{ projectId: string; folderId: string }>;

/**
 * Streams a multipart upload to the backend with the user's access token (the browser never sees tokens).
 * The body is passed through without buffering, so large files do not hit server-action or proxy body limits.
 */
export async function POST(request: NextRequest, { params }: Readonly<{ params: Params }>) {
  const origin = request.headers.get("origin");
  if (origin && origin !== new URL(serverEnv.appBaseUrl).origin) {
    return Response.json({ status: 403, detail: "Cross-origin upload rejected" }, { status: 403 });
  }
  const session = await getSession();
  if (!session) {
    return Response.json({ status: 401, detail: "Authentication required" }, { status: 401 });
  }
  const { projectId, folderId } = await params;
  const target = new URL(
    `/api/v1/projects/${encodeURIComponent(projectId)}/folders/${encodeURIComponent(folderId)}/documents`,
    serverEnv.apiBaseUrl,
  );
  const upstream = await fetch(target, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${session.accessToken}`,
      "Content-Type": request.headers.get("content-type") ?? "application/octet-stream",
      Accept: "application/json",
    },
    body: request.body,
    duplex: "half",
  } as RequestInit & { duplex: "half" });
  return new Response(upstream.body, {
    status: upstream.status,
    headers: { "Content-Type": upstream.headers.get("content-type") ?? "application/json" },
  });
}
