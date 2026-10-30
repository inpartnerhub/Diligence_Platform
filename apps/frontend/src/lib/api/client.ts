import "server-only";

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
