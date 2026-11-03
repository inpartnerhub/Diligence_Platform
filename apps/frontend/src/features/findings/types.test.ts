import { describe, expect, it } from "vitest";

import {
  FINDING_STATUSES,
  REMEDIATION_STATUSES,
  SPA_PROTECTION_TYPES,
  findingPermissions,
  heatMap,
  levelFor,
  needsReason,
  nextStatuses,
  sourcedPercent,
} from "./types";

const analyst = findingPermissions(["ANALYST"], true);
const pm = findingPermissions(["PROJECT_MANAGER"], true);
const partner = findingPermissions(["PARTNER"], true);
const client = findingPermissions(["CLIENT"], true);

describe("finding workflow in the UI", () => {
  const base = { riskLevel: "HIGH" as const, documentCount: 1, createdBy: "author" };

  it("submits only rated findings with a source", () => {
    expect(nextStatuses({ ...base, status: "DRAFT" }, analyst, "author")).toEqual(["IN_REVIEW"]);
    expect(nextStatuses({ ...base, status: "DRAFT", documentCount: 0 }, analyst, "author")).toEqual([]);
    expect(nextStatuses({ ...base, status: "DRAFT", riskLevel: null }, pm, "x")).toEqual(["ARCHIVED"]);
  });

  it("applies four eyes and partner release", () => {
    expect(nextStatuses({ ...base, status: "IN_REVIEW" }, pm, "author")).toEqual(["DRAFT", "ARCHIVED"]);
    expect(nextStatuses({ ...base, status: "IN_REVIEW" }, pm, "reviewer")).toEqual([
      "APPROVED",
      "DRAFT",
      "ARCHIVED",
    ]);
    expect(nextStatuses({ ...base, status: "IN_REVIEW" }, analyst, "reviewer")).toEqual([]);
    expect(nextStatuses({ ...base, status: "APPROVED" }, pm, "x")).toEqual(["DRAFT", "ARCHIVED"]);
    expect(nextStatuses({ ...base, status: "APPROVED" }, partner, "x")).toEqual([
      "RELEASED",
      "DRAFT",
      "ARCHIVED",
    ]);
    expect(nextStatuses({ ...base, status: "RELEASED" }, pm, "x")).toEqual([]);
    expect(nextStatuses({ ...base, status: "RELEASED" }, partner, "x")).toEqual(["ARCHIVED"]);
    expect(nextStatuses({ ...base, status: "RELEASED" }, client, "x")).toEqual([]);
  });

  it("asks for reasons when returning or archiving", () => {
    expect(needsReason("IN_REVIEW", "DRAFT")).toBe(true);
    expect(needsReason("DRAFT", "ARCHIVED")).toBe(true);
    expect(needsReason("IN_REVIEW", "APPROVED")).toBe(false);
  });
});

describe("risk helpers", () => {
  const matrix = { mediumFrom: 5, highFrom: 10, criticalFrom: 17 };

  it("rates like Appendix A", () => {
    expect(levelFor(4, matrix)).toBe("LOW");
    expect(levelFor(5, matrix)).toBe("MEDIUM");
    expect(levelFor(16, matrix)).toBe("HIGH");
    expect(levelFor(25, matrix)).toBe("CRITICAL");
  });

  it("counts heat-map cells and sourced findings", () => {
    const findings = [
      { impact: 4, likelihood: 4, status: "DRAFT" as const, documentCount: 1 },
      { impact: 4, likelihood: 4, status: "RELEASED" as const, documentCount: 0 },
      { impact: 5, likelihood: 1, status: "ARCHIVED" as const, documentCount: 0 },
      { impact: null, likelihood: null, status: "DRAFT" as const, documentCount: 2 },
    ];
    expect([...heatMap(findings).entries()]).toEqual([["4:4", 2]]);
    expect(sourcedPercent(findings)).toBe(67);
    expect(sourcedPercent([])).toBeNull();
  });

  it("exposes expected SPA and remediation constants", () => {
    expect(SPA_PROTECTION_TYPES).toContain("PRICE_CHIP");
    expect(SPA_PROTECTION_TYPES).toContain("SPECIAL_INDEMNITY");
    expect(SPA_PROTECTION_TYPES).toContain("PRE_CLOSING_CONDITION");
    expect(SPA_PROTECTION_TYPES).toContain("POST_CLOSING_COVENANT");
    expect(SPA_PROTECTION_TYPES).toContain("WARRANTY_REPRESENTATION");

    expect(FINDING_STATUSES).toContain("RELEASED");
    expect(REMEDIATION_STATUSES).toEqual(["OPEN", "IN_PROGRESS", "MITIGATED", "WAIVED"]);
  });

  it("handles template rating with matrix", () => {
    const template = {
      defaultImpact: 4,
      defaultLikelihood: 3,
    };
    const score = template.defaultImpact * template.defaultLikelihood;
    expect(score).toBe(12);
    expect(levelFor(score, matrix)).toBe("HIGH");
  });
});
