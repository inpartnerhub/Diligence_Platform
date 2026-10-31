import { describe, expect, it } from "vitest";

import { backendUrl } from "./backendUrl";

const base = "http://kong:8000";

describe("backend URLs", () => {
  it("keeps ordinary paths and queries", () => {
    expect(backendUrl("/api/v1/projects/p-1/search?q=a..b", base).href).toBe(
      "http://kong:8000/api/v1/projects/p-1/search?q=a..b",
    );
  });

  it("rejects IDs that would move the request to another endpoint", () => {
    const id = encodeURIComponent("..");
    expect(() => backendUrl(`/api/v1/projects/p-1/documents/${id}/download`, base)).toThrow(/dot segments/);
    expect(() => backendUrl("/api/v1/projects/./members", base)).toThrow(/dot segments/);
  });

  it("leaves encoded dots alone, since they stay inside one segment", () => {
    const id = encodeURIComponent("%2e%2e");
    expect(backendUrl(`/api/v1/projects/${id}`, base).pathname).toBe("/api/v1/projects/%252e%252e");
  });
});
