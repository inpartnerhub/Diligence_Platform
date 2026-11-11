"use server";

import { revalidatePath } from "next/cache";

import { ApiError, apiFetch } from "@/lib/api/client";
import { stepUpFlag } from "@/lib/auth/stepUpFlag";
import type { Role } from "@/lib/auth/roles";
import { logError } from "@/lib/log";

import type {
  ActionResult,
  SearchReindexRun,
  InviteUserInput,
  OrganizationStatus,
  OrganizationType,
  WebhookDeliveryLog,
  WebhookSubscription,
  WebhookSubscriptionInput,
  WebhookTestResult,
} from "./types";

async function run(path: string, call: () => Promise<unknown>): Promise<ActionResult> {
  try {
    await call();
    revalidatePath(path);
    return { ok: true };
  } catch (error) {
    if (error instanceof ApiError && error.problem.status < 500) {
      return { ok: false, message: error.message, ...stepUpFlag(error) };
    }
    logError("Admin action failed", error);
    return { ok: false, message: "" }; // UI shows its generic, translated error
  }
}

export async function inviteUser(input: InviteUserInput): Promise<ActionResult> {
  return run("/admin/users", () =>
    apiFetch("/api/v1/admin/users/invitations", { method: "POST", body: input }),
  );
}

export async function changeUserRole(userId: string, role: Role): Promise<ActionResult> {
  return run("/admin/users", () =>
    apiFetch(`/api/v1/admin/users/${encodeURIComponent(userId)}/role`, { method: "PATCH", body: { role } }),
  );
}

export async function setUserEnabled(userId: string, enabled: boolean): Promise<ActionResult> {
  const action = enabled ? "enable" : "disable";
  return run("/admin/users", () =>
    apiFetch(`/api/v1/admin/users/${encodeURIComponent(userId)}/${action}`, { method: "POST" }),
  );
}

export async function createOrganization(
  name: string,
  type: OrganizationType,
  defaultRetentionDays?: number,
): Promise<ActionResult> {
  return run("/admin/organizations", () =>
    apiFetch("/api/v1/admin/organizations", {
      method: "POST",
      body: { name, type, defaultRetentionDays },
    }),
  );
}

export async function updateOrganization(
  id: string,
  input: { name?: string; status?: OrganizationStatus; defaultRetentionDays?: number },
): Promise<ActionResult> {
  return run("/admin/organizations", () =>
    apiFetch(`/api/v1/admin/organizations/${encodeURIComponent(id)}`, {
      method: "PATCH",
      body: input,
    }),
  );
}

export async function setOrganizationStatus(id: string, status: OrganizationStatus): Promise<ActionResult> {
  return run("/admin/organizations", () =>
    apiFetch(`/api/v1/admin/organizations/${encodeURIComponent(id)}`, { method: "PATCH", body: { status } }),
  );
}

export async function createGroup(name: string, description?: string): Promise<ActionResult> {
  return run("/admin/groups", () =>
    apiFetch("/api/v1/admin/groups", { method: "POST", body: { name, description } }),
  );
}

export async function replaceGroupMembers(groupId: string, userIds: string[]): Promise<ActionResult> {
  return run("/admin/groups", () =>
    apiFetch(`/api/v1/admin/groups/${encodeURIComponent(groupId)}/members`, {
      method: "PUT",
      body: { userIds },
    }),
  );
}

export async function createWebhook(
  input: WebhookSubscriptionInput,
): Promise<ActionResult<WebhookSubscription>> {
  try {
    const data = await apiFetch<WebhookSubscription>("/api/v1/admin/webhooks", {
      method: "POST",
      body: input,
    });
    revalidatePath("/admin/webhooks");
    return { ok: true, data };
  } catch (error) {
    if (error instanceof ApiError && error.problem.status < 500) {
      return { ok: false, message: error.message, ...stepUpFlag(error) };
    }
    logError("Create webhook failed", error);
    return { ok: false, message: "" };
  }
}

export async function updateWebhook(
  id: string,
  input: Partial<WebhookSubscriptionInput>,
): Promise<ActionResult<WebhookSubscription>> {
  try {
    const data = await apiFetch<WebhookSubscription>(`/api/v1/admin/webhooks/${encodeURIComponent(id)}`, {
      method: "PUT",
      body: input,
    });
    revalidatePath("/admin/webhooks");
    return { ok: true, data };
  } catch (error) {
    if (error instanceof ApiError && error.problem.status < 500) {
      return { ok: false, message: error.message, ...stepUpFlag(error) };
    }
    logError("Update webhook failed", error);
    return { ok: false, message: "" };
  }
}

export async function rotateWebhookSigningKey(id: string): Promise<ActionResult<WebhookSubscription>> {
  try {
    const data = await apiFetch<WebhookSubscription>(
      `/api/v1/admin/webhooks/${encodeURIComponent(id)}/rotate-signing-key`,
      { method: "POST" },
    );
    revalidatePath("/admin/webhooks");
    return { ok: true, data };
  } catch (error) {
    if (error instanceof ApiError && error.problem.status < 500) {
      return { ok: false, message: error.message, ...stepUpFlag(error) };
    }
    logError("Rotate webhook signing key failed", error);
    return { ok: false, message: "" };
  }
}

export async function deleteWebhook(id: string): Promise<ActionResult> {
  return run("/admin/webhooks", () =>
    apiFetch(`/api/v1/admin/webhooks/${encodeURIComponent(id)}`, {
      method: "DELETE",
    }),
  );
}

export async function testWebhook(id: string): Promise<ActionResult<WebhookTestResult>> {
  try {
    const data = await apiFetch<WebhookTestResult>(`/api/v1/admin/webhooks/${encodeURIComponent(id)}/test`, {
      method: "POST",
    });
    revalidatePath("/admin/webhooks");
    return { ok: true, data };
  } catch (error) {
    if (error instanceof ApiError && error.problem.status < 500) {
      return { ok: false, message: error.message, ...stepUpFlag(error) };
    }
    logError("Test webhook failed", error);
    return { ok: false, message: "" };
  }
}

export async function loadWebhookDeliveries(id: string): Promise<ActionResult<WebhookDeliveryLog[]>> {
  try {
    const data = await apiFetch<WebhookDeliveryLog[]>(
      `/api/v1/admin/webhooks/${encodeURIComponent(id)}/deliveries`,
    );
    return { ok: true, data };
  } catch (error) {
    if (error instanceof ApiError && error.problem.status < 500) {
      return { ok: false, message: error.message, ...stepUpFlag(error) };
    }
    logError("Load webhook deliveries failed", error);
    return { ok: false, message: "" };
  }
}

/** Starts a bulk search re-index (#80). Needs a recent sign-in; 409 while a run is going on. */
export async function startSearchReindex(): Promise<ActionResult<SearchReindexRun>> {
  try {
    const data = await apiFetch<SearchReindexRun>("/api/v1/admin/search/reindex", { method: "POST" });
    revalidatePath("/admin/search");
    return { ok: true, data };
  } catch (error) {
    if (error instanceof ApiError && error.problem.status < 500) {
      return { ok: false, message: error.message, ...stepUpFlag(error) };
    }
    logError("Start search re-index failed", error);
    return { ok: false, message: "" };
  }
}
