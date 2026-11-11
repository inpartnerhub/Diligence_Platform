"use server";

import { revalidatePath } from "next/cache";

import { ApiError, apiFetch } from "@/lib/api/client";
import { stepUpFlag } from "@/lib/auth/stepUpFlag";
import { logError } from "@/lib/log";

import type { Report, ReportStatusHistoryItem } from "./types";

type Result<T = undefined> = { ok: true; data?: T } | { ok: false; message: string; stepUp?: true };

const base = (projectId: string, suffix = "") =>
  `/api/v1/projects/${encodeURIComponent(projectId)}/reports${suffix}` as `/api/${string}`;

async function run<T>(projectId: string, call: () => Promise<T>): Promise<Result<T>> {
  try {
    const data = await call();
    revalidatePath(`/projects/${encodeURIComponent(projectId)}/reports`);
    return { ok: true, data };
  } catch (error) {
    if (error instanceof ApiError && error.problem.status < 500) {
      return { ok: false, message: error.message, ...stepUpFlag(error) };
    }
    logError("Report action failed", error);
    return { ok: false, message: "" };
  }
}

export async function generateReport(
  projectId: string,
  title: string,
  language: "id" | "en",
  findingIds?: string[],
) {
  return run(projectId, () =>
    apiFetch<Report>(base(projectId), { method: "POST", body: { title, language, findingIds } }),
  );
}

export async function generateReportRevision(
  projectId: string,
  reportId: string,
  major: boolean,
  title?: string,
  language?: "id" | "en",
  findingIds?: string[],
) {
  return run(projectId, () =>
    apiFetch<Report>(base(projectId, `/${encodeURIComponent(reportId)}/revision`), {
      method: "POST",
      body: { major, title, language, findingIds },
    }),
  );
}

export async function submitReport(projectId: string, reportId: string, note?: string) {
  return run(projectId, () =>
    apiFetch<Report>(base(projectId, `/${encodeURIComponent(reportId)}/submit`), {
      method: "POST",
      body: { note },
    }),
  );
}

export async function approveReport(projectId: string, reportId: string, note?: string) {
  return run(projectId, () =>
    apiFetch<Report>(base(projectId, `/${encodeURIComponent(reportId)}/approve`), {
      method: "POST",
      body: { note },
    }),
  );
}

export async function releaseReport(projectId: string, reportId: string, note?: string) {
  return run(projectId, () =>
    apiFetch<Report>(base(projectId, `/${encodeURIComponent(reportId)}/release`), {
      method: "POST",
      body: { note },
    }),
  );
}

export async function returnReportDraft(projectId: string, reportId: string, reason: string) {
  return run(projectId, () =>
    apiFetch<Report>(base(projectId, `/${encodeURIComponent(reportId)}/return`), {
      method: "POST",
      body: { reason },
    }),
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

export async function fetchReportHistory(projectId: string, reportId: string) {
  return run(projectId, () =>
    apiFetch<ReportStatusHistoryItem[]>(base(projectId, `/${encodeURIComponent(reportId)}/history`)),
  );
}
