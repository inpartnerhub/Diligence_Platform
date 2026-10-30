import "server-only";

import { apiFetch } from "@/lib/api/client";

import type { DataRoomDocument, Folder } from "./types";

const base = (projectId: string) => `/api/v1/projects/${encodeURIComponent(projectId)}` as const;

export const dataRoomApi = {
  folders: (projectId: string) => apiFetch<Folder[]>(`${base(projectId)}/folders`),
  documents: (projectId: string, folderId: string) =>
    apiFetch<DataRoomDocument[]>(`${base(projectId)}/folders/${encodeURIComponent(folderId)}/documents`),
};
