import { describe, expect, it } from "vitest";

import { reindexPercent } from "./types";

describe("reindexPercent", () => {
  it("counts indexed and failed documents against the total", () => {
    expect(reindexPercent({ documentsTotal: 200, indexed: 90, failed: 10 })).toBe(50);
  });

  it("is 0 before the total is known", () => {
    expect(reindexPercent({ documentsTotal: 0, indexed: 0, failed: 0 })).toBe(0);
  });

  it("never exceeds 100 when documents were added during the run", () => {
    expect(reindexPercent({ documentsTotal: 10, indexed: 12, failed: 0 })).toBe(100);
  });

  it("rounds down so 100 means done", () => {
    expect(reindexPercent({ documentsTotal: 1000, indexed: 999, failed: 0 })).toBe(99);
  });
});
