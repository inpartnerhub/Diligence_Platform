import "server-only";

import { headers as requestHeaders } from "next/headers";

import { requireSession } from "@/lib/auth/session";
import { serverEnv } from "@/lib/config/env";

/** RFC 9457 problem details, as returned by the backend for every error. */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status: number;
  detail?: string;
  instance?: string;
}

export class ApiError extends Error {
  constructor(readonly problem: ProblemDetail) {
    super(problem.detail ?? problem.title ?? `API request failed with status ${problem.status}`);
    this.name = "ApiError";
  }
}

interface RequestOptions extends Omit<RequestInit, "body"> {
  /** Defaults to the signed-in user's access token. */
  accessToken?: string;
  body?: unknown;
}

/**
 * Server-side API client. All backend traffic goes through Kong (`API_BASE_URL`) with the user's access token
 * from the server-side session; the browser never holds tokens or calls the backend directly. Response types come
 * from `schema.gen.ts`, generated from the OpenAPI spec (`npm run generate:api`).
 */
export async function apiFetch<T>(path: `/api/${string}`, options: RequestOptions = {}): Promise<T> {
  const { accessToken, body, headers, ...init } = options;
  const token = accessToken ?? (await requireSession()).accessToken;
  const response = await fetch(new URL(path, serverEnv.apiBaseUrl), {
    ...init,
    headers: {
      Accept: "application/json",
      Authorization: `Bearer ${token}`,
      ...(await forwardedFor()),
      ...(body !== undefined && { "Content-Type": "application/json" }),
      ...headers,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: init.cache ?? "no-store",
  });

  if (!response.ok) {
    const problem = (await response.json().catch(() => ({}))) as Partial<ProblemDetail>;
    throw new ApiError({ ...problem, status: response.status });
  }
  return (response.status === 204 ? undefined : await response.json()) as T;
}

/**
 * The browser's address for audit logs and watermarks (PRD ADM-03, DOC-04). The backend only sees this server, so
 * the address the ingress reported to us is passed on; outside a request (or without a proxy) nothing is sent.
 */
export async function forwardedFor(): Promise<Record<string, string>> {
  try {
    const incoming = await requestHeaders();
    const address =
      incoming.get("x-forwarded-for")?.split(",")[0]?.trim() || incoming.get("x-real-ip")?.trim();
    return address ? { "X-Forwarded-For": address } : {};
  } catch {
    return {};
  }
}
