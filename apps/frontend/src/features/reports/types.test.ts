import { describe, expect, it } from "vitest";

import { reportPermissions } from "./types";

describe("report permissions", () => {
  it("follows the project roles", () => {
    expect(reportPermissions(["PARTNER"], true)).toEqual({ generate: true, issue: true, word: true });
    expect(reportPermissions(["PROJECT_MANAGER"], true)).toEqual({
      generate: true,
      issue: false,
      word: true,
    });
    expect(reportPermissions(["ANALYST"], true)).toEqual({ generate: false, issue: false, word: true });
    expect(reportPermissions(["CLIENT"], true)).toEqual({ generate: false, issue: false, word: false });
    expect(reportPermissions(["PARTNER"], false)).toEqual({ generate: false, issue: false, word: true });
  });
});
