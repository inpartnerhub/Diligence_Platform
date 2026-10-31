import "server-only";

import { getSession } from "@/lib/auth/session";
import { serverEnv } from "@/lib/config/env";

import { backendUrl } from "./backendUrl";
import { forwardedFor } from "./client";

const PASSED_HEADERS = ["content-type", "content-disposition", "content-length"];

/**
 * Streams a binary backend response (preview pages, downloads) to the browser with the user's access token. Nothing
 * is cached: every response is watermarked for its viewer.
 */
export async function proxyGet(path: string, search = ""): Promise<Response> {
  const session = await getSession();
  if (!session) {
    return Response.json({ status: 401, detail: "Authentication required" }, { status: 401 });
  }
  const url = safeBackendUrl(path + search);
  if (!url) return invalidPath();
  const upstream = await fetch(url, {
    headers: { Authorization: `Bearer ${session.accessToken}`, ...(await forwardedFor()) },
    cache: "no-store",
  });
  const responseHeaders = new Headers({ "Cache-Control": "no-store, private" });
  for (const name of PASSED_HEADERS) {
    const value = upstream.headers.get(name);
    if (value) responseHeaders.set(name, value);
  }
  return new Response(upstream.body, { status: upstream.status, headers: responseHeaders });
}

/**
 * Streams a multipart upload to the backend with the user's access token (the browser never sees tokens). The body
 * is passed through without buffering, so large files do not hit server-action or proxy body limits. Cross-origin
 * posts are rejected.
 */
export async function proxyMultipart(request: Request, path: string): Promise<Response> {
  const origin = request.headers.get("origin");
  if (origin && origin !== new URL(serverEnv.appBaseUrl).origin) {
    return Response.json({ status: 403, detail: "Cross-origin upload rejected" }, { status: 403 });
  }
  const session = await getSession();
  if (!session) {
    return Response.json({ status: 401, detail: "Authentication required" }, { status: 401 });
  }
  const url = safeBackendUrl(path);
  if (!url) return invalidPath();
  const upstream = await fetch(url, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${session.accessToken}`,
      "Content-Type": request.headers.get("content-type") ?? "application/octet-stream",
      Accept: "application/json",
      ...(await forwardedFor()),
    },
    body: request.body,
    duplex: "half",
  } as RequestInit & { duplex: "half" });
  return new Response(upstream.body, {
    status: upstream.status,
    headers: { "Content-Type": upstream.headers.get("content-type") ?? "application/json" },
  });
}

export const segment = (value: string) => encodeURIComponent(value);

function safeBackendUrl(path: string): URL | undefined {
  try {
    return backendUrl(path, serverEnv.apiBaseUrl);
  } catch {
    return undefined;
  }
}

const invalidPath = () =>
  Response.json({ status: 400, detail: "Invalid resource identifier" }, { status: 400 });
