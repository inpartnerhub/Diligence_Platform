import "server-only";

import { apiFetch } from "@/lib/api/client";

import type { DataRoomDocument, DocumentClause, DocumentClauseSummary, Folder } from "./types";

const base = (projectId: string) => `/api/v1/projects/${encodeURIComponent(projectId)}` as const;

export const dataRoomApi = {
  folders: (projectId: string) => apiFetch<Folder[]>(`${base(projectId)}/folders`),
  documents: (projectId: string, folderId: string) =>
    apiFetch<DataRoomDocument[]>(`${base(projectId)}/folders/${encodeURIComponent(folderId)}/documents`),
  clauses: (projectId: string, documentId: string) =>
    apiFetch<DocumentClause[]>(`${base(projectId)}/documents/${encodeURIComponent(documentId)}/clauses`),
  projectClauses: (projectId: string, query?: { type?: string; q?: string }) => {
    const params = new URLSearchParams();
    if (query?.type) params.set("type", query.type);
    if (query?.q) params.set("q", query.q);
    const qs = params.toString() ? `?${params.toString()}` : "";
    return apiFetch<DocumentClauseSummary[]>(`${base(projectId)}/clauses${qs}`);
  },
};
