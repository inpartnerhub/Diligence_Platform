import { describe, expect, it } from "vitest";

import { allowedProjectRoles, type Project } from "./types";

describe("project types and role permissions", () => {
  it("determines allowed project roles for partner", () => {
    expect(allowedProjectRoles("PARTNER")).toEqual(["PARTNER", "PROJECT_MANAGER", "ANALYST", "OBSERVER"]);
  });

  it("determines allowed project roles for client and target", () => {
    expect(allowedProjectRoles("CLIENT")).toEqual(["CLIENT"]);
    expect(allowedProjectRoles("TARGET")).toEqual(["TARGET"]);
  });

  it("supports purged status and retention metadata on Project type", () => {
    const project: Project = {
      id: "p-1",
      code: "DD-001",
      name: "Project Alpha",
      clientOrganizationId: "org-1",
      clientName: "Client Org",
      targetOrganizationId: "org-2",
      targetName: "Target Org",
      stage: "CLOSED",
      status: "PURGED",
      startDate: "2026-01-01",
      endDate: "2026-06-01",
      templateId: null,
      retentionPeriodDays: 90,
      retentionDeadline: "2026-09-01T00:00:00Z",
      purgedAt: "2026-09-02T00:00:00Z",
      createdAt: "2026-01-01T00:00:00Z",
      archivedAt: "2026-06-01T00:00:00Z",
      myRoles: ["PARTNER"],
    };
    expect(project.status).toBe("PURGED");
    expect(project.retentionPeriodDays).toBe(90);
    expect(project.purgedAt).toBeDefined();
  });
});
