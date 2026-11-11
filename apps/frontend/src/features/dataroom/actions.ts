"use server";

import { revalidatePath } from "next/cache";

import { ApiError, apiFetch } from "@/lib/api/client";
import { logError } from "@/lib/log";

import type {
  AnnotationCreateRequest,
  AnnotationUpdateRequest,
  DataRoomDocument,
  DocumentAnnotation,
  DocumentClause,
  DocumentClauseSummary,
  DocumentVersion,
  Folder,
  Grant,
  PreviewInfo,
  SearchResponse,
} from "./types";

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
    logError("Data room request failed", error);
    return { ok: false, message: "" };
  }
}

export async function loadAnnotations(
  projectId: string,
  documentId: string,
): Promise<Result<DocumentAnnotation[]>> {
  return attempt(() =>
    apiFetch<DocumentAnnotation[]>(
      `/api/v1/projects/${encodeURIComponent(projectId)}/documents/${encodeURIComponent(documentId)}/annotations`,
    ),
  );
}

export async function loadAnnotationsByFinding(
  projectId: string,
  findingId: string,
): Promise<Result<DocumentAnnotation[]>> {
  return attempt(() =>
    apiFetch<DocumentAnnotation[]>(
      `/api/v1/projects/${encodeURIComponent(projectId)}/annotations?findingId=${encodeURIComponent(findingId)}`,
    ),
  );
}

export async function createAnnotation(
  projectId: string,
  documentId: string,
  request: AnnotationCreateRequest,
): Promise<Result<DocumentAnnotation>> {
  return attempt(() =>
    apiFetch<DocumentAnnotation>(
      `/api/v1/projects/${encodeURIComponent(projectId)}/documents/${encodeURIComponent(documentId)}/annotations`,
      {
        method: "POST",
        body: request,
      },
    ),
  );
}

export async function updateAnnotation(
  projectId: string,
  documentId: string,
  annotationId: string,
  request: AnnotationUpdateRequest,
): Promise<Result<DocumentAnnotation>> {
  return attempt(() =>
    apiFetch<DocumentAnnotation>(
      `/api/v1/projects/${encodeURIComponent(projectId)}/documents/${encodeURIComponent(documentId)}/annotations/${encodeURIComponent(annotationId)}`,
      {
        method: "PUT",
        body: request,
      },
    ),
  );
}

export async function deleteAnnotation(
  projectId: string,
  documentId: string,
  annotationId: string,
): Promise<Result> {
  return attempt(() =>
    apiFetch(
      `/api/v1/projects/${encodeURIComponent(projectId)}/documents/${encodeURIComponent(documentId)}/annotations/${encodeURIComponent(annotationId)}`,
      {
        method: "DELETE",
      },
    ),
  ).then(drop);
}

export async function loadDocumentClauses(
  projectId: string,
  documentId: string,
): Promise<Result<DocumentClause[]>> {
  return attempt(() =>
    apiFetch<DocumentClause[]>(
      `/api/v1/projects/${encodeURIComponent(projectId)}/documents/${encodeURIComponent(documentId)}/clauses`,
    ),
  );
}

export async function listProjectClauses(
  projectId: string,
  filters?: { type?: string; q?: string },
): Promise<Result<DocumentClauseSummary[]>> {
  const query = new URLSearchParams();
  if (filters?.type) query.set("type", filters.type);
  if (filters?.q) query.set("q", filters.q);
  const qs = query.toString() ? `?${query.toString()}` : "";
  return attempt(() =>
    apiFetch<DocumentClauseSummary[]>(`/api/v1/projects/${encodeURIComponent(projectId)}/clauses${qs}`),
  );
}

export async function triggerClauseExtraction(
  projectId: string,
  documentId: string,
): Promise<Result<DocumentClause[]>> {
  return attempt(() =>
    apiFetch<DocumentClause[]>(
      `/api/v1/projects/${encodeURIComponent(projectId)}/documents/${encodeURIComponent(documentId)}/extract-clauses`,
      {
        method: "POST",
      },
    ),
  );
}

export async function refreshDataRoom(projectId: string): Promise<void> {
  revalidatePath(`/projects/${encodeURIComponent(projectId)}/data-room`);
}

function drop<T>(result: Result<T>): Result {
  return result.ok ? { ok: true } : result;
}
