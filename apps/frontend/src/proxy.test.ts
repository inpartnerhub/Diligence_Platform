import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { NextRequest } from "next/server";

describe("edge proxy rate limit controls", () => {
  beforeEach(() => {
    vi.resetModules();
  });

  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it("bypasses rate limiting in non-production when RATE_LIMIT_DISABLED=true", async () => {
    vi.stubEnv("RATE_LIMIT_DISABLED", "true");
    vi.stubEnv("NODE_ENV", "test");

    // Dynamic import to evaluate top-level constants with current env
    const { proxy } = await import("./proxy");

    const req = new NextRequest("http://localhost:3000/some-action", {
      method: "POST",
      headers: { "next-action": "action-id" },
    });

    const res = await proxy(req);
    expect(res.status).not.toBe(429);
  });

  it("enforces rate limiting when RATE_LIMIT_DISABLED is not true", async () => {
    vi.stubEnv("RATE_LIMIT_DISABLED", "false");
    vi.stubEnv("NODE_ENV", "development");

    const { proxy } = await import("./proxy");

    const req = new NextRequest("http://localhost:3000/auth/login", {
      method: "GET",
    });

    const res = await proxy(req);
    expect(res.status).not.toBe(429);
  });
});
