import { describe, expect, it } from "vitest";

import { GET } from "./route";

describe("/auth/no-access", () => {
  it("explains the missing role and offers to sign in again", async () => {
    const response = GET();

    expect(response.status).toBe(200);
    expect(response.headers.get("Content-Type")).toContain("text/html");
    const body = await response.text();
    expect(body).toContain("No access yet");
    expect(body).toContain('href="/auth/login"');
  });
});
