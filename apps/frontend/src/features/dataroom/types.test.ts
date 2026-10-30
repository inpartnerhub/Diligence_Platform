import { describe, expect, it } from "vitest";

import { buildTree, formatBytes, splitHighlight, type Folder } from "./types";

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
