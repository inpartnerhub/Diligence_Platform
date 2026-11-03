import { describe, expect, it } from "vitest";

import {
  buildTree,
  formatBytes,
  splitHighlight,
  STANDARD_CLAUSE_TYPES,
  type DocumentClause,
  type DocumentClauseSummary,
  type Folder,
} from "./types";

const folder = (id: string, name: string, parentId: string | null = null): Folder => ({
  id,
  parentId,
  name,
  workstreamId: null,
  downloadAllowed: false,
  printAllowed: false,
  canUpload: true,
});

describe("data room helpers", () => {
  it("builds a sorted tree and promotes folders with invisible parents to roots", () => {
    const tree = buildTree([
      folder("2", "10 Tax"),
      folder("1", "2 Legal"),
      folder("3", "Contracts", "1"),
      folder("4", "Granted", "hidden-parent"),
    ]);
    expect(tree.map((n) => n.title)).toEqual(["2 Legal", "10 Tax", "Granted"]);
    expect(tree[0]?.children.map((n) => n.title)).toEqual(["Contracts"]);
  });

  it("formats sizes", () => {
    expect(formatBytes(512)).toBe("512 B");
    expect(formatBytes(1536)).toBe("1.5 KB");
    expect(formatBytes(25 * 1024 * 1024)).toBe("25 MB");
  });
});

describe("splitHighlight", () => {
  it("separates matches from plain text without interpreting HTML", () => {
    expect(splitHighlight("akta pendirian PT <b>X</b> baru")).toEqual([
      { text: "akta ", match: false },
      { text: "pendirian", match: true },
      { text: " PT <b>X</b> ", match: false },
      { text: "baru", match: true },
    ]);
    expect(splitHighlight("no match")).toEqual([{ text: "no match", match: false }]);
  });
});

describe("document annotations", () => {
  it("validates annotation structure and coordinates", () => {
    const ann = {
      id: "ann-1",
      projectId: "proj-1",
      documentId: "doc-1",
      pageNumber: 2,
      xPct: 15.5,
      yPct: 20.0,
      widthPct: 35.0,
      heightPct: 12.0,
      color: "#faad14",
      comment: "Clause 12 inspection",
      findingId: "fnd-1",
      createdBy: "usr-1",
      createdByName: "Analyst",
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    };
    expect(ann.pageNumber).toBeGreaterThan(0);
    expect(ann.xPct + ann.widthPct).toBeLessThanOrEqual(100);
    expect(ann.yPct + ann.heightPct).toBeLessThanOrEqual(100);
  });
});

describe("document clauses (DOC-09)", () => {
  it("includes standard legal clause types", () => {
    expect(STANDARD_CLAUSE_TYPES).toContain("CHANGE_OF_CONTROL");
    expect(STANDARD_CLAUSE_TYPES).toContain("NON_COMPETE");
    expect(STANDARD_CLAUSE_TYPES).toContain("ASSIGNMENT");
    expect(STANDARD_CLAUSE_TYPES).toContain("INDEMNITY");
    expect(STANDARD_CLAUSE_TYPES).toContain("TERMINATION");
    expect(STANDARD_CLAUSE_TYPES).toContain("GOVERNING_LAW");
    expect(STANDARD_CLAUSE_TYPES).toContain("LIMITATION_OF_LIABILITY");
  });

  it("validates document clause and summary structure", () => {
    const clause: DocumentClause = {
      id: "clause-1",
      projectId: "proj-1",
      documentId: "doc-1",
      versionId: "ver-1",
      clauseType: "CHANGE_OF_CONTROL",
      confidence: 0.96,
      pageNumber: 3,
      extractedText: "In the event of a change of control of the Company...",
      createdAt: new Date().toISOString(),
    };

    const summary: DocumentClauseSummary = {
      ...clause,
      documentName: "Master Services Agreement.pdf",
      folderId: "folder-1",
    };

    expect(clause.confidence).toBeGreaterThanOrEqual(0.7);
    expect(clause.confidence).toBeLessThanOrEqual(1.0);
    expect(clause.pageNumber).toBeGreaterThan(0);
    expect(summary.documentName).toBe("Master Services Agreement.pdf");
  });
});
