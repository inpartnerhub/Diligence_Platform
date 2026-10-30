"use server";

import { revalidatePath } from "next/cache";

import type { DataRoomDocument, Folder } from "@/features/dataroom/types";
import { ApiError, apiFetch } from "@/lib/api/client";

import type { ItemInput, RequestComment, RequestItem, RequestItemDetail, RequestStatus } from "./types";

type Result<T = undefined> = { ok: true; data?: T } | { ok: false; message: string };

const base = (projectId: string, suffix = "") =>
  `/api/v1/projects/${encodeURIComponent(projectId)}/requests${suffix}` as `/api/${string}`;
const item = (projectId: string, itemId: string, suffix = "") =>
  base(projectId, `/${encodeURIComponent(itemId)}${suffix}`);

async function run<T>(projectId: string, call: () => Promise<T>): Promise<Result<T>> {
  const result = await attempt(call);
  if (result.ok) revalidatePath(`/projects/${encodeURIComponent(projectId)}/requests`);
  return result;
}

async function attempt<T>(call: () => Promise<T>): Promise<Result<T>> {
  try {
    return { ok: true, data: await call() };
  } catch (error) {
    if (error instanceof ApiError && error.problem.status < 500) {
      return { ok: false, message: error.message };
    }
    console.error("Request list action failed", error);
    return { ok: false, message: "" };
  }
}

export async function loadItem(projectId: string, itemId: string): Promise<Result<RequestItemDetail>> {
  return attempt(() => apiFetch<RequestItemDetail>(item(projectId, itemId)));
}

export async function createItem(projectId: string, input: ItemInput): Promise<Result<RequestItem>> {
  return run(projectId, () => apiFetch<RequestItem>(base(projectId), { method: "POST", body: input }));
}

export async function updateItem(
  projectId: string,
  itemId: string,
  input: ItemInput,
): Promise<Result<RequestItem>> {
  return run(projectId, () => apiFetch<RequestItem>(item(projectId, itemId), { method: "PUT", body: input }));
}

export async function deleteItem(projectId: string, itemId: string): Promise<Result> {
  return run(projectId, () => apiFetch<undefined>(item(projectId, itemId), { method: "DELETE" }));
}

export async function releaseWave(
  projectId: string,
  wave: number,
  workstreamId?: string,
): Promise<Result<{ released: number }>> {
  return run(projectId, () =>
    apiFetch<{ released: number }>(base(projectId, "/release"), {
      method: "POST",
      body: { wave, workstreamId },
    }),
  );
}

export async function changeStatus(
  projectId: string,
  itemId: string,
  status: RequestStatus,
  reason?: string,
): Promise<Result<RequestItem>> {
  return run(projectId, () =>
    apiFetch<RequestItem>(item(projectId, itemId, "/status"), { method: "POST", body: { status, reason } }),
  );
}

export async function linkDocument(
  projectId: string,
  itemId: string,
  documentId: string,
  comment?: string,
): Promise<Result> {
  return run(projectId, () =>
    apiFetch<undefined>(item(projectId, itemId, "/documents"), {
      method: "POST",
      body: { documentId, comment },
    }),
  );
}

export async function unlinkDocument(projectId: string, itemId: string, documentId: string): Promise<Result> {
  return run(projectId, () =>
    apiFetch<undefined>(item(projectId, itemId, `/documents/${encodeURIComponent(documentId)}`), {
      method: "DELETE",
    }),
  );
}

export async function addComment(
  projectId: string,
  itemId: string,
  body: string,
  internal: boolean,
): Promise<Result<RequestComment>> {
  return run(projectId, () =>
    apiFetch<RequestComment>(item(projectId, itemId, "/comments"), {
      method: "POST",
      body: { body, internal },
    }),
  );
}

/** Data-room folders the caller can see, for linking or uploading delivered documents. */
export async function loadFolders(projectId: string): Promise<Result<Folder[]>> {
  return attempt(() => apiFetch<Folder[]>(`/api/v1/projects/${encodeURIComponent(projectId)}/folders`));
}

export async function loadFolderDocuments(
  projectId: string,
  folderId: string,
): Promise<Result<DataRoomDocument[]>> {
  return attempt(() =>
    apiFetch<DataRoomDocument[]>(
      `/api/v1/projects/${encodeURIComponent(projectId)}/folders/${encodeURIComponent(folderId)}/documents`,
    ),
  );
}

export async function refreshRequests(projectId: string): Promise<void> {
  revalidatePath(`/projects/${encodeURIComponent(projectId)}/requests`);
}
