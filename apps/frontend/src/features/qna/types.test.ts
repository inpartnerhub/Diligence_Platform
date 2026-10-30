import { describe, expect, it } from "vitest";

import { moveKey, needsFeedback, qnaPermissions, teamMoves } from "./types";

describe("Q&A permissions and workflow", () => {
  it("derives permissions from project roles", () => {
    expect(qnaPermissions(["ANALYST"], true)).toMatchObject({
      edit: true,
      manage: false,
      ask: true,
      answer: false,
    });
    expect(qnaPermissions(["CLIENT"], true)).toMatchObject({ edit: false, ask: true, internal: false });
    expect(qnaPermissions(["TARGET"], true)).toMatchObject({ answer: true, ask: false });
    expect(qnaPermissions(["PARTNER"], false)).toMatchObject({ edit: false, ask: false, internal: true });
  });

  it("offers the team's moves", () => {
    const analyst = qnaPermissions(["ANALYST"], true);
    const pm = qnaPermissions(["PROJECT_MANAGER"], true);
    expect(teamMoves("DRAFT", analyst)).toEqual(["PENDING_APPROVAL"]);
    expect(teamMoves("DRAFT", pm)).toEqual(["PENDING_APPROVAL", "SENT"]);
    expect(teamMoves("PENDING_APPROVAL", analyst)).toEqual([]);
    expect(teamMoves("PENDING_APPROVAL", pm)).toEqual(["SENT", "DRAFT"]);
    expect(teamMoves("ANSWERED", analyst)).toEqual(["CLOSED", "SENT"]);
    expect(teamMoves("CLOSED", pm)).toEqual([]);
    expect(teamMoves("SENT", qnaPermissions(["TARGET"], true))).toEqual([]);
  });

  it("asks for messages where the backend needs them", () => {
    expect(needsFeedback("PENDING_APPROVAL", "DRAFT")).toBe(true);
    expect(needsFeedback("ANSWERED", "SENT")).toBe(true);
    expect(needsFeedback("SENT", "CLOSED")).toBe(true);
    expect(needsFeedback("ANSWERED", "CLOSED")).toBe(false);
    expect(moveKey("ANSWERED", "SENT")).toBe("followUp");
    expect(moveKey("SENT", "CLOSED")).toBe("withdraw");
    expect(moveKey("PENDING_APPROVAL", "DRAFT")).toBe("return");
    expect(moveKey("DRAFT", "SENT")).toBe("SENT");
  });
});
