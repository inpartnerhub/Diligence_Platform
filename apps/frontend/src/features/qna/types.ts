import type { ProjectRole } from "@/features/projects/types";

// Mirrors the backend DTOs in com.inpartnerhub.diligence.qna.internal.

export const QUESTION_STATUSES = ["DRAFT", "PENDING_APPROVAL", "SENT", "ANSWERED", "CLOSED"] as const;
export type QuestionStatus = (typeof QUESTION_STATUSES)[number];

export const STATUS_COLORS: Record<QuestionStatus, string> = {
  DRAFT: "default",
  PENDING_APPROVAL: "gold",
  SENT: "processing",
  ANSWERED: "cyan",
  CLOSED: "success",
};

export interface Question {
  id: string;
  number: string;
  status: QuestionStatus;
  body: string;
  workstreamId: string | null;
  requestItemId: string | null;
  documentId: string | null;
  findingId: string | null;
  askedBy: string;
  askedByName: string | null;
  recipientUserId: string | null;
  recipientName: string | null;
  dueDate: string | null;
  feedback: string | null;
  createdAt: string;
  sentAt: string | null;
  answeredAt: string | null;
  closedAt: string | null;
  answered: boolean;
}

export interface QuestionDetail {
  question: Question;
  answer: { body: string; answeredByName: string | null; answeredAt: string } | null;
  documents: {
    id: string;
    name: string;
    contentType: string;
    scanStatus: string;
    linkedByName: string | null;
    linkedAt: string;
  }[];
  references: { requestItemNumber: string | null; documentName: string | null; findingNumber: string | null };
  history: {
    fromStatus: QuestionStatus | null;
    toStatus: QuestionStatus;
    changedByName: string | null;
    changedAt: string;
    note: string | null;
  }[];
}

export interface QuestionInput {
  body: string;
  workstreamId?: string | null;
  requestItemId?: string | null;
  documentId?: string | null;
  findingId?: string | null;
  recipientUserId?: string | null;
  dueDate?: string | null;
  submit?: boolean;
}

/** What the caller may do in Q&A, from their project roles (the backend enforces the same). */
export interface QnaPermissions {
  edit: boolean;
  manage: boolean;
  answer: boolean;
  ask: boolean;
  internal: boolean;
}

export function qnaPermissions(roles: readonly ProjectRole[], active: boolean): QnaPermissions {
  const internal = roles.some((r) => ["PARTNER", "PROJECT_MANAGER", "ANALYST", "OBSERVER"].includes(r));
  const edit = active && roles.some((r) => ["PARTNER", "PROJECT_MANAGER", "ANALYST"].includes(r));
  return {
    edit,
    manage: active && roles.some((r) => ["PARTNER", "PROJECT_MANAGER"].includes(r)),
    answer: active && !internal && roles.includes("TARGET"),
    ask: edit || (active && !internal && roles.includes("CLIENT")),
    internal,
  };
}

/** Team workflow moves offered in the UI (mirrors QuestionService.changeStatus). */
export function teamMoves(status: QuestionStatus, permissions: QnaPermissions): QuestionStatus[] {
  if (!permissions.edit) return [];
  switch (status) {
    case "DRAFT":
      return permissions.manage ? ["PENDING_APPROVAL", "SENT"] : ["PENDING_APPROVAL"];
    case "PENDING_APPROVAL":
      return permissions.manage ? ["SENT", "DRAFT"] : [];
    case "SENT":
      return ["CLOSED"];
    case "ANSWERED":
      return ["CLOSED", "SENT"];
    default:
      return [];
  }
}

/** Moves that need a message: returning, following up, withdrawing a sent question. */
export function needsFeedback(from: QuestionStatus, to: QuestionStatus): boolean {
  return (
    (from === "PENDING_APPROVAL" && to === "DRAFT") ||
    (from === "ANSWERED" && to === "SENT") ||
    (from === "SENT" && to === "CLOSED")
  );
}

/** The action label key for a move ("follow up" and "withdraw" differ from plain status changes). */
export function moveKey(from: QuestionStatus, to: QuestionStatus): string {
  if (from === "ANSWERED" && to === "SENT") return "followUp";
  if (from === "SENT" && to === "CLOSED") return "withdraw";
  if (from === "PENDING_APPROVAL" && to === "DRAFT") return "return";
  return to;
}
