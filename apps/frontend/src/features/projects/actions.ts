"use server";

import { revalidatePath } from "next/cache";

import type { Page } from "@/features/admin/types";
import { ApiError, apiFetch } from "@/lib/api/client";
import { withQuery } from "@/lib/api/query";
import { sanitizeOptionalText, sanitizeText, validateRequiredText } from "@/lib/security/inputSanitizer";

import type {
  ActionResult,
  CreateProjectInput,
  Project,
  ProjectRole,
  ProjectStage,
  TemplateInput,
  UserSummary,
  WorkstreamType,
} from "./types";

async function run<T>(paths: string[], call: () => Promise<T>): Promise<ActionResult<T>> {
  try {
    const data = await call();
    paths.forEach((path) => revalidatePath(path));
    return { ok: true, data };
  } catch (error) {
    if (error instanceof ApiError && error.problem.status < 500) {
      return { ok: false, message: error.message };
    }
    console.error("Project action failed", error);
    return { ok: false, message: "" }; // the UI shows its generic, translated error
  }
}

const projectPath = (id: string) => `/projects/${encodeURIComponent(id)}`;
const projectApi = (id: string, suffix = "") =>
  `/api/v1/projects/${encodeURIComponent(id)}${suffix}` as `/api/${string}`;

export async function createProject(input: CreateProjectInput): Promise<ActionResult<Project>> {
  try {
    const code = validateRequiredText(input.code, "Project code", true);
    const name = validateRequiredText(input.name, "Project name", true);
    const sanitizedInput: CreateProjectInput = {
      ...input,
      code,
      name,
    };
    return run(["/"], () => apiFetch<Project>("/api/v1/projects", { method: "POST", body: sanitizedInput }));
  } catch (error) {
    return { ok: false, message: error instanceof Error ? error.message : "Validation failed" };
  }
}

export async function updateProject(
  id: string,
  input: { name?: string; startDate?: string; endDate?: string; stage?: ProjectStage },
): Promise<ActionResult> {
  try {
    let name = input.name;
    if (name !== undefined) {
      name = validateRequiredText(name, "Project name", true);
    }
    const sanitizedInput = {
      ...input,
      ...(name !== undefined && { name }),
    };
    return run([projectPath(id)], () =>
      apiFetch(projectApi(id), { method: "PATCH", body: sanitizedInput }),
    ).then(drop);
  } catch (error) {
    return { ok: false, message: error instanceof Error ? error.message : "Validation failed" };
  }
}

export async function setProjectArchived(id: string, archived: boolean): Promise<ActionResult> {
  const action = archived ? "/archive" : "/reactivate";
  return run([projectPath(id), "/"], () => apiFetch(projectApi(id, action), { method: "POST" })).then(drop);
}

export async function replaceWorkstreams(id: string, workstreams: WorkstreamType[]): Promise<ActionResult> {
  return run([projectPath(id)], () =>
    apiFetch(projectApi(id, "/workstreams"), { method: "PUT", body: { workstreams } }),
  ).then(drop);
}

export async function addMember(
  id: string,
  input: { userId: string; role: ProjectRole; workstreamId?: string; accessExpiresAt?: string },
): Promise<ActionResult> {
  return run([projectPath(id)], () =>
    apiFetch(projectApi(id, "/members"), { method: "POST", body: input }),
  ).then(drop);
}

export async function removeMember(id: string, memberId: string): Promise<ActionResult> {
  return run([projectPath(id)], () =>
    apiFetch(projectApi(id, `/members/${encodeURIComponent(memberId)}`), { method: "DELETE" }),
  ).then(drop);
}

/** Remote search for the member picker; scoped to an organization for client/target members. */
export async function searchUsers(q: string, organizationId?: string): Promise<UserSummary[]> {
  try {
    const sanitizedQ = sanitizeText(q, true);
    const page = await apiFetch<Page<UserSummary>>(
      withQuery("/api/v1/directory/users", { q: sanitizedQ, organizationId, size: 20 }),
    );
    return page.items;
  } catch {
    return [];
  }
}

export async function saveTemplate(id: string | null, input: TemplateInput): Promise<ActionResult> {
  try {
    const name = validateRequiredText(input.name, "Template name", true);
    const description = sanitizeOptionalText(input.description);
    const sanitizedInput: TemplateInput = {
      ...input,
      name,
      description,
    };
    return run(["/admin/templates"], () =>
      id
        ? apiFetch(`/api/v1/project-templates/${encodeURIComponent(id)}`, {
            method: "PUT",
            body: sanitizedInput,
          })
        : apiFetch("/api/v1/project-templates", { method: "POST", body: sanitizedInput }),
    ).then(drop);
  } catch (error) {
    return { ok: false, message: error instanceof Error ? error.message : "Validation failed" };
  }
}

export async function duplicateTemplate(id: string, name: string): Promise<ActionResult> {
  try {
    const sanitizedName = validateRequiredText(name, "Template name", true);
    return run(["/admin/templates"], () =>
      apiFetch(`/api/v1/project-templates/${encodeURIComponent(id)}/duplicate`, {
        method: "POST",
        body: { name: sanitizedName },
      }),
    ).then(drop);
  } catch (error) {
    return { ok: false, message: error instanceof Error ? error.message : "Validation failed" };
  }
}

function drop<T>(result: ActionResult<T>): ActionResult {
  return result.ok ? { ok: true } : result;
}
