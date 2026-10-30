import { describe, expect, it } from "vitest";

import { isOverdue, nextStatuses, requestPermissions, unreleasedByWave, waves } from "./types";

describe("request list permissions", () => {
  it("follows the project roles", () => {
    expect(requestPermissions(["PROJECT_MANAGER"], true)).toEqual({
      edit: true,
      release: true,
      deliver: false,
    });
    expect(requestPermissions(["ANALYST"], true)).toEqual({ edit: true, release: false, deliver: false });
    expect(requestPermissions(["TARGET"], true)).toEqual({ edit: false, release: false, deliver: true });
    expect(requestPermissions(["OBSERVER"], true)).toEqual({ edit: false, release: false, deliver: false });
    expect(requestPermissions(["PARTNER"], false)).toEqual({ edit: false, release: false, deliver: false });
  });
});

describe("nextStatuses", () => {
  const manager = requestPermissions(["PROJECT_MANAGER"], true);
  const analyst = requestPermissions(["ANALYST"], true);
  const target = requestPermissions(["TARGET"], true);

  it("offers the target delivery reports on open items with documents only", () => {
    expect(nextStatuses("REQUESTED", target, false)).toEqual([]);
    expect(nextStatuses("REJECTED", target, true)).toEqual(["PARTIAL", "RECEIVED"]);
    expect(nextStatuses("RECEIVED", target, true)).toEqual([]);
  });

  it("mirrors the internal workflow", () => {
    expect(nextStatuses("NOT_REQUESTED", manager, false)).toEqual(["REQUESTED", "CLOSED"]);
    expect(nextStatuses("NOT_REQUESTED", analyst, false)).toEqual(["CLOSED"]);
    expect(nextStatuses("REQUESTED", manager, true)).toEqual(["PARTIAL", "RECEIVED", "CLOSED"]);
    expect(nextStatuses("RECEIVED", manager, true)).toEqual(["PARTIAL", "REJECTED", "CLOSED"]);
    expect(nextStatuses("CLOSED", requestPermissions(["OBSERVER"], true), true)).toEqual([]);
  });
});

describe("list helpers", () => {
  it("detects overdue open items", () => {
    expect(isOverdue({ status: "REQUESTED", dueDate: "2026-11-01" }, "2026-11-02")).toBe(true);
    expect(isOverdue({ status: "REQUESTED", dueDate: "2026-11-02" }, "2026-11-02")).toBe(false);
    expect(isOverdue({ status: "CLOSED", dueDate: "2026-10-01" }, "2026-11-02")).toBe(false);
    expect(isOverdue({ status: "REJECTED", dueDate: null }, "2026-11-02")).toBe(false);
  });

  it("groups waves", () => {
    const items = [
      { wave: 2, status: "NOT_REQUESTED" as const },
      { wave: 1, status: "REQUESTED" as const },
      { wave: 2, status: "NOT_REQUESTED" as const },
      { wave: 3, status: "NOT_REQUESTED" as const },
    ];
    expect(waves(items)).toEqual([1, 2, 3]);
    expect([...unreleasedByWave(items).entries()]).toEqual([
      [2, 2],
      [3, 1],
    ]);
  });
});
