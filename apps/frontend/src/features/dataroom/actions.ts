"use server";

import { revalidatePath } from "next/cache";

import { ApiError, apiFetch } from "@/lib/api/client";

import type { DataRoomDocument, DocumentVersion, Folder, Grant, PreviewInfo, SearchResponse } from "./types";

type Result<T = undefined> = { ok: true; data?: T } | { ok: false; message: string };

async function run<T>(projectId: string, call: () => Promise<T>): Promise<Result<T>> {
  const result = await attempt(call);
  if (result.ok) revalidatePath(`/projects/${encodeURIComponent(projectId)}/data-room`);
  return result;
}

const folders = (projectId: string, suffix = "") =>
  `/api/v1/projects/${encodeURIComponent(projectId)}/folders${suffix}` as `/api/${string}`;

/** Folders the caller can see, for attaching documents from other features (requests, Q&A, findings). */
export async function loadFolders(projectId: string): Promise<Result<Folder[]>> {
  return attempt(() => apiFetch<Folder[]>(folders(projectId)));
}

export async function loadFolderDocuments(
  projectId: string,
  folderId: string,
): Promise<Result<DataRoomDocument[]>> {
  return attempt(() =>
    apiFetch<DataRoomDocument[]>(folders(projectId, `/${encodeURIComponent(folderId)}/documents`)),
  );
}

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

/** Opens a document in the viewer (logged as a view by the backend). */
export async function openPreview(
  projectId: string,
  documentId: string,
  versionId?: string,
): Promise<Result<PreviewInfo>> {
  const query = versionId ? `?versionId=${encodeURIComponent(versionId)}` : "";
  return attempt(() =>
    apiFetch<PreviewInfo>(
      `/api/v1/projects/${encodeURIComponent(projectId)}/documents/${encodeURIComponent(documentId)}/preview${query}`,
    ),
  );
}

export async function searchDocuments(
  projectId: string,
  q: string,
  page = 0,
): Promise<Result<SearchResponse>> {
  const query = new URLSearchParams({ q, page: String(page), size: "20" });
  return attempt(() =>
    apiFetch<SearchResponse>(`/api/v1/projects/${encodeURIComponent(projectId)}/search?${query}`),
  );
}

async function attempt<T>(call: () => Promise<T>): Promise<Result<T>> {
  try {
    return { ok: true, data: await call() };
  } catch (error) {
    if (error instanceof ApiError && error.problem.status < 500) {
      return { ok: false, message: error.message };
    }
    console.error("Data room request failed", error);
    return { ok: false, message: "" };
  }
}

export async function refreshDataRoom(projectId: string): Promise<void> {
  revalidatePath(`/projects/${encodeURIComponent(projectId)}/data-room`);
}

function drop<T>(result: Result<T>): Result {
  return result.ok ? { ok: true } : result;
}
