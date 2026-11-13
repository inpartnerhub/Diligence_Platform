import { beforeEach, describe, expect, it, vi } from "vitest";

// getSession talks to Next.js cookies, Redis and Keycloak; all three are replaced here (#174).
vi.mock("server-only", () => ({}));
vi.mock("next/headers", () => ({ cookies: vi.fn(), headers: vi.fn() }));
vi.mock("next/navigation", () => ({ redirect: vi.fn() }));
vi.mock("iron-session", () => ({ getIronSession: vi.fn(async () => ({ sid: "sid-1" })) }));
vi.mock("./oidc", () => ({ oidcConfiguration: vi.fn() }));
vi.mock("@/lib/config/env", () => ({ serverEnv: { sessionSecret: "x".repeat(32), secureCookies: false } }));
vi.mock("./session-store", () => ({ readSession: vi.fn(), updateSession: vi.fn(), deleteSession: vi.fn() }));
vi.mock("openid-client", () => ({ refreshTokenGrant: vi.fn() }));

import * as client from "openid-client";

import { getSession } from "./session";
import { deleteSession, readSession, updateSession } from "./session-store";

const jwt = (payload: object) => `h.${Buffer.from(JSON.stringify(payload)).toString("base64url")}.s`;
const now = () => Math.floor(Date.now() / 1000);

describe("getSession refresh (#174)", () => {
  beforeEach(() => {
    vi.mocked(deleteSession).mockReset().mockResolvedValue(undefined);
    vi.mocked(updateSession).mockReset().mockResolvedValue(undefined);
    vi.mocked(readSession).mockResolvedValue({
      accessToken: "old",
      refreshToken: "refresh",
      expiresAt: now() - 1, // access token expired: refresh needed
      createdAt: now() - 50 * 60, // signed in 50 minutes ago
      user: { id: "u", name: "U", email: "u@x.test", roles: ["ANALYST"] },
    });
  });

  it("keeps an active user signed in past 30 minutes with the new refresh token's lifetime", async () => {
    vi.mocked(client.refreshTokenGrant).mockResolvedValue({
      access_token: jwt({ sub: "u", realm_access: { roles: ["analyst"] } }),
      refresh_token: "refresh-2",
      expires_in: 300,
      refresh_expires_in: 1800,
      claims: () => undefined,
    } as never);

    const session = await getSession();

    expect(session?.refreshToken).toBe("refresh-2");
    expect(updateSession).toHaveBeenCalledWith("sid-1", expect.anything(), 1800);
    expect(deleteSession).not.toHaveBeenCalled();
  });

  it("deletes the stored session when the refresh is refused", async () => {
    vi.mocked(client.refreshTokenGrant).mockRejectedValue(new Error("invalid_grant"));

    expect(await getSession()).toBeNull();
    expect(deleteSession).toHaveBeenCalledWith("sid-1");
    expect(updateSession).not.toHaveBeenCalled();
  });
});
