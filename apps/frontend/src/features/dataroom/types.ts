// Mirrors the backend DTOs in com.inpartnerhub.diligence.dataroom.internal.

export type ScanStatus = "PENDING" | "CLEAN" | "INFECTED" | "REJECTED" | "FAILED";

export interface Folder {
  id: string;
  parentId: string | null;
  name: string;
  workstreamId: string | null;
  downloadAllowed: boolean;
  printAllowed: boolean;
  canUpload: boolean;
}

export interface DataRoomDocument {
  id: string;
  folderId: string;
  name: string;
  contentType: string;
  sizeBytes: number;
  sha256: string;
  scanStatus: ScanStatus;
  currentVersionId: string;
  createdAt: string;
}

export interface DocumentVersion {
  id: string;
  versionNumber: number;
  fileName: string;
  contentType: string;
  detectedContentType: string | null;
  sizeBytes: number;
  sha256: string;
  scanStatus: ScanStatus;
  scanDetail: string | null;
  scannedAt: string | null;
  uploadedBy: string;
  uploadedAt: string;
}

export type PreviewStatus = "PENDING" | "READY" | "UNAVAILABLE" | "FAILED";

export interface PreviewInfo {
  documentId: string;
  versionId: string;
  versionNumber: number;
  name: string;
  previewStatus: PreviewStatus;
  pageCount: number;
  downloadAllowed: boolean;
  printAllowed: boolean;
  pdfAvailable: boolean;
  originalAllowed: boolean;
}

export interface SearchResult {
  documentId: string;
  versionId: string;
  folderId: string;
  name: string;
  contentType: string;
  score: number;
  highlights: string[];
}

export interface SearchResponse {
  results: SearchResult[];
  total: number;
  page: number;
  size: number;
}

/** Backend highlight markers (private-use characters, never HTML). */
export const MATCH_START = "\uE000";
export const MATCH_END = "\uE001";

/** Splits a highlight fragment into plain and matched parts, to render without HTML injection. */
export function splitHighlight(fragment: string): { text: string; match: boolean }[] {
  const parts: { text: string; match: boolean }[] = [];
  for (const chunk of fragment.split(MATCH_START)) {
    const end = chunk.indexOf(MATCH_END);
    if (end < 0) {
      if (chunk) parts.push({ text: chunk, match: false });
      continue;
    }
    if (end > 0) parts.push({ text: chunk.slice(0, end), match: true });
    const rest = chunk.slice(end + MATCH_END.length);
    if (rest) parts.push({ text: rest, match: false });
  }
  return parts;
}

export interface UploadResponse {
  document: DataRoomDocument;
  version: DocumentVersion;
  unchanged: boolean;
  duplicateOf: string[];
}

export type PrincipalType = "ROLE" | "USER" | "GROUP";
export type Permission = "VIEW" | "UPLOAD";

export interface Grant {
  type: PrincipalType;
  principalId: string | null;
  permission: Permission;
}

export interface TreeNode {
  key: string;
  title: string;
  folder: Folder;
  children: TreeNode[];
}

/** Builds the folder tree; folders whose parent is not visible (target side) become roots. */
export function buildTree(folders: Folder[]): TreeNode[] {
  const nodes = new Map<string, TreeNode>(
    folders.map((folder) => [folder.id, { key: folder.id, title: folder.name, folder, children: [] }]),
  );
  const roots: TreeNode[] = [];
  for (const node of nodes.values()) {
    const parent = node.folder.parentId ? nodes.get(node.folder.parentId) : undefined;
    (parent ? parent.children : roots).push(node);
  }
  const sort = (list: TreeNode[]) => {
    list.sort((a, b) => a.title.localeCompare(b.title, undefined, { numeric: true }));
    list.forEach((n) => sort(n.children));
  };
  sort(roots);
  return roots;
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const units = ["KB", "MB", "GB"];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value.toFixed(value < 10 ? 1 : 0)} ${units[unit]}`;
}
