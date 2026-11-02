"use server";

import { revalidatePath } from "next/cache";

import { ApiError, apiFetch } from "@/lib/api/client";
import { sanitizeOptionalText, validateRequiredText } from "@/lib/security/inputSanitizer";

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
  try {
    const description = validateRequiredText(input.description, "Description");
    const category = sanitizeOptionalText(input.category, true);
    const number = sanitizeOptionalText(input.number, true);
    const sanitizedInput: ItemInput = {
      ...input,
      description,
      ...(category !== undefined && { category }),
      ...(number !== undefined && { number }),
    };
    return run(projectId, () =>
      apiFetch<RequestItem>(base(projectId), { method: "POST", body: sanitizedInput }),
    );
  } catch (error) {
    return { ok: false, message: error instanceof Error ? error.message : "Validation failed" };
  }
}

export async function updateItem(
  projectId: string,
  itemId: string,
  input: ItemInput,
): Promise<Result<RequestItem>> {
  try {
    const description = validateRequiredText(input.description, "Description");
    const category = sanitizeOptionalText(input.category, true);
    const number = sanitizeOptionalText(input.number, true);
    const sanitizedInput: ItemInput = {
      ...input,
      description,
      ...(category !== undefined && { category }),
      ...(number !== undefined && { number }),
    };
    return run(projectId, () =>
      apiFetch<RequestItem>(item(projectId, itemId), { method: "PUT", body: sanitizedInput }),
    );
  } catch (error) {
    return { ok: false, message: error instanceof Error ? error.message : "Validation failed" };
  }
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
  const sanitizedReason = sanitizeOptionalText(reason);
  return run(projectId, () =>
    apiFetch<RequestItem>(item(projectId, itemId, "/status"), {
      method: "POST",
      body: { status, ...(sanitizedReason !== undefined && { reason: sanitizedReason }) },
    }),
  );
}

export async function linkDocument(
  projectId: string,
  itemId: string,
  documentId: string,
  comment?: string,
): Promise<Result> {
  const sanitizedComment = sanitizeOptionalText(comment);
  return run(projectId, () =>
    apiFetch<undefined>(item(projectId, itemId, "/documents"), {
      method: "POST",
      body: { documentId, ...(sanitizedComment !== undefined && { comment: sanitizedComment }) },
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
  try {
    const sanitizedBody = validateRequiredText(body, "Comment");
    return run(projectId, () =>
      apiFetch<RequestComment>(item(projectId, itemId, "/comments"), {
        method: "POST",
        body: { body: sanitizedBody, internal },
      }),
    );
  } catch (error) {
    return { ok: false, message: error instanceof Error ? error.message : "Validation failed" };
  }
}

export async function refreshRequests(projectId: string): Promise<void> {
  revalidatePath(`/projects/${encodeURIComponent(projectId)}/requests`);
}
