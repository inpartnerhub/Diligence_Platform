import "server-only";

import { getSession } from "@/lib/auth/session";
import { serverEnv } from "@/lib/config/env";

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
  const upstream = await fetch(new URL(path + search, serverEnv.apiBaseUrl), {
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

export const segment = (value: string) => encodeURIComponent(value);
