import "server-only";

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
  accessToken?: string;
  body?: unknown;
}

/**
 * Server-side API client. All backend traffic goes through Kong (`API_BASE_URL`); the browser never calls the
 * backend directly. Response types come from `schema.gen.ts`, generated from the OpenAPI spec (`npm run generate:api`).
 */
export async function apiFetch<T>(path: `/api/${string}`, options: RequestOptions = {}): Promise<T> {
  const { accessToken, body, headers, ...init } = options;
  const response = await fetch(new URL(path, serverEnv.apiBaseUrl), {
    ...init,
    headers: {
      Accept: "application/json",
      ...(body !== undefined && { "Content-Type": "application/json" }),
      ...(accessToken && { Authorization: `Bearer ${accessToken}` }),
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
