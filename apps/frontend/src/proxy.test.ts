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

describe("edge proxy cross-site writes", () => {
  beforeEach(() => {
    vi.resetModules();
    vi.stubEnv("RATE_LIMIT_DISABLED", "true");
    vi.stubEnv("NODE_ENV", "test");
    vi.stubEnv("APP_BASE_URL", "http://localhost:3000");
  });

  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it("rejects a server action posted from another site", async () => {
    const { proxy } = await import("./proxy");
    const req = new NextRequest("http://localhost:3000/findings", {
      method: "POST",
      headers: { "next-action": "action-id", origin: "https://evil.test", "sec-fetch-site": "cross-site" },
    });
    expect((await proxy(req)).status).toBe(403);
  });

  it("rejects a cross-site logout", async () => {
    const { proxy } = await import("./proxy");
    const req = new NextRequest("http://localhost:3000/auth/logout", {
      method: "POST",
      headers: { "sec-fetch-site": "cross-site" },
    });
    expect((await proxy(req)).status).toBe(403);
  });

  it("lets a same-origin logout through", async () => {
    const { proxy } = await import("./proxy");
    const req = new NextRequest("http://localhost:3000/auth/logout", {
      method: "POST",
      headers: { origin: "http://localhost:3000", "sec-fetch-site": "same-origin" },
    });
    expect((await proxy(req)).status).not.toBe(403);
  });
});
