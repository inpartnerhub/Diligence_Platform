import { describe, expect, it } from "vitest";

import { DEFAULT_REFRESH_SECONDS, SESSION_MAX_SECONDS, sessionTtlSeconds } from "./session-lifetime";

describe("session lifetime (#174)", () => {
  const signIn = 1_000_000;

  it("follows the refresh token, so an active user stays signed in past 30 minutes", () => {
    // 50 minutes after the sign-in Keycloak hands out a fresh 30-minute refresh token.
    expect(sessionTtlSeconds(1800, signIn, signIn + 50 * 60)).toBe(1800);
  });

  it("never outlives the maximum session age", () => {
    expect(sessionTtlSeconds(1800, signIn, signIn + SESSION_MAX_SECONDS - 600)).toBe(600);
    expect(sessionTtlSeconds(1800, signIn, signIn + SESSION_MAX_SECONDS + 1)).toBe(0);
  });

  it("falls back to the idle timeout when the token response has no refresh lifetime", () => {
    expect(sessionTtlSeconds(undefined, signIn, signIn)).toBe(DEFAULT_REFRESH_SECONDS);
    expect(sessionTtlSeconds("not a number", undefined, signIn)).toBe(DEFAULT_REFRESH_SECONDS);
  });
});
