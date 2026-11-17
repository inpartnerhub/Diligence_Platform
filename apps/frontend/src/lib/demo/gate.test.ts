import { describe, expect, it } from "vitest";

import { demoGatePassword, demoGateToken, isDemoGateOpen, sameString } from "./gate";

const env = (values: Record<string, string>) => values as unknown as NodeJS.ProcessEnv;

describe("public demo gate", () => {
  it("stays off unless DEMO_PUBLIC is set with a password of at least 8 characters", () => {
    expect(demoGatePassword(env({ DEMO_GATE_PASSWORD: "long-enough" }))).toBeUndefined();
    expect(demoGatePassword(env({ DEMO_PUBLIC: "true" }))).toBeUndefined();
    expect(demoGatePassword(env({ DEMO_PUBLIC: "true", DEMO_GATE_PASSWORD: "short" }))).toBeUndefined();
    expect(demoGatePassword(env({ DEMO_PUBLIC: "true", DEMO_GATE_PASSWORD: "long-enough" }))).toBe(
      "long-enough",
    );
  });

  it("opens only for the cookie made from the current password and secret", async () => {
    const config = env({
      DEMO_PUBLIC: "true",
      DEMO_GATE_PASSWORD: "long-enough",
      SESSION_SECRET: "s".repeat(32),
    });
    const token = await demoGateToken("long-enough", "s".repeat(32));
    expect(await isDemoGateOpen(token, config)).toBe(true);
    expect(await isDemoGateOpen(undefined, config)).toBe(false);
    expect(await isDemoGateOpen(await demoGateToken("other-password", "s".repeat(32)), config)).toBe(false);
    expect(await isDemoGateOpen(token, { ...config, SESSION_SECRET: "t".repeat(32) })).toBe(false);
    expect(token).not.toContain("long-enough");
  });

  it("compares strings of any length", () => {
    expect(sameString("abc", "abc")).toBe(true);
    expect(sameString("abc", "abd")).toBe(false);
    expect(sameString("abc", "abcd")).toBe(false);
    expect(sameString("", "")).toBe(true);
  });
});
