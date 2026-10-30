"use server";

import { revalidatePath } from "next/cache";

import { ApiError, apiFetch } from "@/lib/api/client";

import type { Finding, FindingDetail, FindingInput, FindingStatus, RiskMatrix } from "./types";

type Result<T = undefined> = { ok: true; data?: T } | { ok: false; message: string };

const base = (projectId: string, suffix = "") =>
  `/api/v1/projects/${encodeURIComponent(projectId)}/findings${suffix}` as `/api/${string}`;
const one = (projectId: string, findingId: string, suffix = "") =>
  base(projectId, `/${encodeURIComponent(findingId)}${suffix}`);

async function attempt<T>(call: () => Promise<T>): Promise<Result<T>> {
  try {
    return { ok: true, data: await call() };
  } catch (error) {
    if (error instanceof ApiError && error.problem.status < 500) {
      return { ok: false, message: error.message };
    }
    console.error("Finding action failed", error);
    return { ok: false, message: "" };
  }
}

async function run<T>(projectId: string, call: () => Promise<T>): Promise<Result<T>> {
  const result = await attempt(call);
  if (result.ok) revalidatePath(`/projects/${encodeURIComponent(projectId)}/findings`);
  return result;
}

export async function loadFinding(projectId: string, findingId: string): Promise<Result<FindingDetail>> {
  return attempt(() => apiFetch<FindingDetail>(one(projectId, findingId)));
}

export async function createFinding(projectId: string, input: FindingInput): Promise<Result<Finding>> {
  return run(projectId, () => apiFetch<Finding>(base(projectId), { method: "POST", body: input }));
}

export async function updateFinding(
  projectId: string,
  findingId: string,
  input: FindingInput,
): Promise<Result<Finding>> {
  return run(projectId, () => apiFetch<Finding>(one(projectId, findingId), { method: "PUT", body: input }));
}

export async function deleteFinding(projectId: string, findingId: string): Promise<Result> {
  return run(projectId, () => apiFetch<undefined>(one(projectId, findingId), { method: "DELETE" }));
}

export async function changeFindingStatus(
  projectId: string,
  findingId: string,
  status: FindingStatus,
  reason?: string,
): Promise<Result<Finding>> {
  return run(projectId, () =>
    apiFetch<Finding>(one(projectId, findingId, "/status"), { method: "POST", body: { status, reason } }),
  );
}

export async function linkFindingDocument(
  projectId: string,
  findingId: string,
  documentId: string,
  pageReference?: string,
  note?: string,
): Promise<Result> {
  return run(projectId, () =>
    apiFetch<undefined>(one(projectId, findingId, "/documents"), {
      method: "POST",
      body: { documentId, pageReference, note },
    }),
  );
}

export async function unlinkFindingDocument(
  projectId: string,
  findingId: string,
  documentId: string,
): Promise<Result> {
  return run(projectId, () =>
    apiFetch<undefined>(one(projectId, findingId, `/documents/${encodeURIComponent(documentId)}`), {
      method: "DELETE",
    }),
  );
}

export async function addFindingNote(projectId: string, findingId: string, body: string): Promise<Result> {
  return run(projectId, () =>
    apiFetch<undefined>(one(projectId, findingId, "/comments"), { method: "POST", body: { body } }),
  );
}

export async function saveRiskMatrix(
  matrix: RiskMatrix,
): Promise<Result<{ matrix: RiskMatrix; rerated: number }>> {
  const result = await attempt(() =>
    apiFetch<{ matrix: RiskMatrix; rerated: number }>("/api/v1/admin/risk-matrix", {
      method: "PUT",
      body: matrix,
    }),
  );
  if (result.ok) revalidatePath("/admin/risk-matrix");
  return result;
}
