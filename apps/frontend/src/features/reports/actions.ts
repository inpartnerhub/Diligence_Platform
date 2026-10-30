"use server";

import { revalidatePath } from "next/cache";

import { ApiError, apiFetch } from "@/lib/api/client";

import type { Report } from "./types";

type Result<T = undefined> = { ok: true; data?: T } | { ok: false; message: string };

const base = (projectId: string, suffix = "") =>
  `/api/v1/projects/${encodeURIComponent(projectId)}/reports${suffix}` as `/api/${string}`;

async function run<T>(projectId: string, call: () => Promise<T>): Promise<Result<T>> {
  try {
    const data = await call();
    revalidatePath(`/projects/${encodeURIComponent(projectId)}/reports`);
    return { ok: true, data };
  } catch (error) {
    if (error instanceof ApiError && error.problem.status < 500) {
      return { ok: false, message: error.message };
    }
    console.error("Report action failed", error);
    return { ok: false, message: "" };
  }
}

export async function generateReport(projectId: string, title: string, language: "id" | "en") {
  return run(projectId, () =>
    apiFetch<Report>(base(projectId), { method: "POST", body: { title, language } }),
  );
}

export async function issueReport(projectId: string, reportId: string) {
  return run(projectId, () =>
    apiFetch<Report>(base(projectId, `/${encodeURIComponent(reportId)}/issue`), { method: "POST" }),
  );
}

export async function deleteReport(projectId: string, reportId: string) {
  return run(projectId, () =>
    apiFetch<undefined>(base(projectId, `/${encodeURIComponent(reportId)}`), { method: "DELETE" }),
  );
}
