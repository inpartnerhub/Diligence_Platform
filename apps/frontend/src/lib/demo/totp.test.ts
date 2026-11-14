import { describe, expect, it } from "vitest";

import { keycloakTotp, totp } from "./totp";

describe("totp", () => {
  // RFC 6238 appendix B test vectors (SHA-1, key "12345678901234567890"), truncated to 6 digits.
  it.each([
    [59, "287082"],
    [1111111109, "081804"],
    [1234567890, "005924"],
    [2000000000, "279037"],
  ])("matches RFC 6238 at %i s", (seconds, code) => {
    expect(totp(Buffer.from("12345678901234567890"), seconds * 1000)).toBe(code);
  });

  it("keys a Keycloak secret with the secret's own bytes", () => {
    expect(keycloakTotp("12345678901234567890", 59_000)).toBe("287082");
  });
});
