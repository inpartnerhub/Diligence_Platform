import { describe, expect, it } from "vitest";

import { messageKey, safeLink } from "./types";

describe("notifications", () => {
  it("only follows application paths", () => {
    expect(safeLink("/projects/1/requests?item=2")).toBe("/projects/1/requests?item=2");
    expect(safeLink("//evil.example")).toBeNull();
    expect(safeLink("https://evil.example")).toBeNull();
    expect(safeLink(null)).toBeNull();
  });

  it("distinguishes closed and rejected reviews", () => {
    expect(messageKey({ type: "REQUEST_REVIEWED", params: { status: "REJECTED" } })).toBe(
      "REQUEST_REVIEWED_REJECTED",
    );
    expect(messageKey({ type: "REQUEST_REVIEWED", params: { status: "CLOSED" } })).toBe(
      "REQUEST_REVIEWED_CLOSED",
    );
    expect(messageKey({ type: "REQUEST_RELEASED", params: {} })).toBe("REQUEST_RELEASED");
  });
});
