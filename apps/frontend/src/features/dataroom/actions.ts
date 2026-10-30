"use server";

import { revalidatePath } from "next/cache";

import { ApiError, apiFetch } from "@/lib/api/client";

import type { DocumentVersion, Grant } from "./types";

type Result<T = undefined> = { ok: true; data?: T } | { ok: false; message: string };

async function run<T>(projectId: string, call: () => Promise<T>): Promise<Result<T>> {
  try {
    const data = await call();
    revalidatePath(`/projects/${encodeURIComponent(projectId)}/data-room`);
    return { ok: true, data };
  } catch (error) {
    if (error instanceof ApiError && error.problem.status < 500) {
      return { ok: false, message: error.message };
    }
    console.error("Data room action failed", error);
    return { ok: false, message: "" };
  }
}

const folders = (projectId: string, suffix = "") =>
  `/api/v1/projects/${encodeURIComponent(projectId)}/folders${suffix}` as `/api/${string}`;

export async function createFolder(projectId: string, name: string, parentId?: string): Promise<Result> {
  return run(projectId, () =>
    apiFetch(folders(projectId), { method: "POST", body: { name, parentId } }),
  ).then(drop);
}

export async function updateFolder(
  projectId: string,
  folderId: string,
  changes: { name?: string; downloadAllowed?: boolean; printAllowed?: boolean },
): Promise<Result> {
  return run(projectId, () =>
    apiFetch(folders(projectId, `/${encodeURIComponent(folderId)}`), { method: "PATCH", body: changes }),
  ).then(drop);
}

export async function deleteFolder(projectId: string, folderId: string): Promise<Result> {
  return run(projectId, () =>
    apiFetch(folders(projectId, `/${encodeURIComponent(folderId)}`), { method: "DELETE" }),
  ).then(drop);
}

export async function loadGrants(projectId: string, folderId: string): Promise<Grant[]> {
  return apiFetch<Grant[]>(folders(projectId, `/${encodeURIComponent(folderId)}/access`));
}

export async function saveGrants(projectId: string, folderId: string, grants: Grant[]): Promise<Result> {
  return run(projectId, () =>
    apiFetch(folders(projectId, `/${encodeURIComponent(folderId)}/access`), {
      method: "PUT",
      body: { grants },
    }),
  ).then(drop);
}

export async function loadVersions(projectId: string, documentId: string): Promise<DocumentVersion[]> {
  return apiFetch<DocumentVersion[]>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/documents/${encodeURIComponent(documentId)}/versions`,
  );
}

export async function refreshDataRoom(projectId: string): Promise<void> {
  revalidatePath(`/projects/${encodeURIComponent(projectId)}/data-room`);
}

function drop<T>(result: Result<T>): Result {
  return result.ok ? { ok: true } : result;
}
