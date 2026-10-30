"use server";

import { revalidatePath } from "next/cache";

import { ApiError, apiFetch } from "@/lib/api/client";

import type { Question, QuestionDetail, QuestionInput, QuestionStatus } from "./types";

type Result<T = undefined> = { ok: true; data?: T } | { ok: false; message: string };

const base = (projectId: string, suffix = "") =>
  `/api/v1/projects/${encodeURIComponent(projectId)}/questions${suffix}` as `/api/${string}`;
const one = (projectId: string, questionId: string, suffix = "") =>
  base(projectId, `/${encodeURIComponent(questionId)}${suffix}`);

async function attempt<T>(call: () => Promise<T>): Promise<Result<T>> {
  try {
    return { ok: true, data: await call() };
  } catch (error) {
    if (error instanceof ApiError && error.problem.status < 500) {
      return { ok: false, message: error.message };
    }
    console.error("Q&A action failed", error);
    return { ok: false, message: "" };
  }
}

async function run<T>(projectId: string, call: () => Promise<T>): Promise<Result<T>> {
  const result = await attempt(call);
  if (result.ok) revalidatePath(`/projects/${encodeURIComponent(projectId)}/qna`);
  return result;
}

export async function loadQuestion(projectId: string, questionId: string): Promise<Result<QuestionDetail>> {
  return attempt(() => apiFetch<QuestionDetail>(one(projectId, questionId)));
}

export async function createQuestion(projectId: string, input: QuestionInput): Promise<Result<Question>> {
  return run(projectId, () => apiFetch<Question>(base(projectId), { method: "POST", body: input }));
}

export async function updateQuestion(
  projectId: string,
  questionId: string,
  input: QuestionInput,
): Promise<Result<Question>> {
  return run(projectId, () => apiFetch<Question>(one(projectId, questionId), { method: "PUT", body: input }));
}

export async function deleteQuestion(projectId: string, questionId: string): Promise<Result> {
  return run(projectId, () => apiFetch<undefined>(one(projectId, questionId), { method: "DELETE" }));
}

export async function changeQuestionStatus(
  projectId: string,
  questionId: string,
  status: QuestionStatus,
  feedback?: string,
): Promise<Result<Question>> {
  return run(projectId, () =>
    apiFetch<Question>(one(projectId, questionId, "/status"), { method: "POST", body: { status, feedback } }),
  );
}

export async function answerQuestion(projectId: string, questionId: string, body: string): Promise<Result> {
  return run(projectId, () =>
    apiFetch<undefined>(one(projectId, questionId, "/answer"), { method: "PUT", body: { body } }),
  );
}

export async function linkAnswerDocument(
  projectId: string,
  questionId: string,
  documentId: string,
): Promise<Result> {
  return run(projectId, () =>
    apiFetch<undefined>(one(projectId, questionId, "/documents"), { method: "POST", body: { documentId } }),
  );
}

export async function unlinkAnswerDocument(
  projectId: string,
  questionId: string,
  documentId: string,
): Promise<Result> {
  return run(projectId, () =>
    apiFetch<undefined>(one(projectId, questionId, `/documents/${encodeURIComponent(documentId)}`), {
      method: "DELETE",
    }),
  );
}
