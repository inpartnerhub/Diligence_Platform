import { randomBytes } from "node:crypto";

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const store = new Map<string, string>();

vi.mock("server-only", () => ({}));
vi.mock("redis", () => {
  const client = {
    on: () => client,
    connect: async () => client,
    get: async (key: string) => store.get(key) ?? null,
    set: async (key: string, value: string) => void store.set(key, value),
    del: async (key: string) => void store.delete(key),
  };
  return { createClient: () => client };
});

import { readSession, type SessionData, writeSession } from "./session-store";

const session: SessionData = {
  accessToken: "access-token-value",
  refreshToken: "refresh-token-value",
  idToken: "id-token-value",
  expiresAt: 1_900_000_000,
  user: { id: "u1", name: "User", email: "user@example.com", roles: [] },
};
const key = () => randomBytes(32).toString("base64");

describe("session store encryption (#172)", () => {
  beforeEach(() => {
    store.clear();
    vi.stubEnv("REDIS_URL", "redis://localhost:6379/1");
    vi.stubEnv("SESSION_ENCRYPTION_KEY", key());
    vi.stubEnv("SESSION_ENCRYPTION_KEY_PREVIOUS", "");
  });

  afterEach(() => vi.unstubAllEnvs());

  it("stores no readable token in Redis and reads the session back", async () => {
    await writeSession("sid", session, 600);

    const raw = store.get("diligence:session:sid") ?? "";
    expect(raw).toMatch(/^enc1\./);
    for (const secret of [
      "access-token-value",
      "refresh-token-value",
      "id-token-value",
      "user@example.com",
    ]) {
      expect(raw).not.toContain(secret);
    }
    expect(await readSession("sid")).toEqual(session);
  });

  it("keeps sessions across a key rotation while the previous key is configured", async () => {
    const old = process.env.SESSION_ENCRYPTION_KEY ?? "";
    await writeSession("sid", session, 600);

    vi.stubEnv("SESSION_ENCRYPTION_KEY", key());
    vi.stubEnv("SESSION_ENCRYPTION_KEY_PREVIOUS", old);
    expect(await readSession("sid")).toEqual(session);

    vi.stubEnv("SESSION_ENCRYPTION_KEY_PREVIOUS", "");
    expect(await readSession("sid")).toBeNull();
  });

  it("treats a plaintext record from before #172 as no session", async () => {
    store.set("diligence:session:sid", JSON.stringify(session));

    expect(await readSession("sid")).toBeNull();
  });
});
