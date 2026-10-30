"use server";

import { revalidatePath } from "next/cache";

import { ApiError, apiFetch } from "@/lib/api/client";
import type { Role } from "@/lib/auth/roles";

import type { ActionResult, InviteUserInput, OrganizationStatus, OrganizationType } from "./types";

async function run(path: string, call: () => Promise<unknown>): Promise<ActionResult> {
  try {
    await call();
    revalidatePath(path);
    return { ok: true };
  } catch (error) {
    if (error instanceof ApiError && error.problem.status < 500) {
      return { ok: false, message: error.message };
    }
    console.error("Admin action failed", error);
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

export async function createOrganization(name: string, type: OrganizationType): Promise<ActionResult> {
  return run("/admin/organizations", () =>
    apiFetch("/api/v1/admin/organizations", { method: "POST", body: { name, type } }),
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
