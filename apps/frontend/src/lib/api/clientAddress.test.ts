import { describe, expect, it } from "vitest";

import { clientAddress } from "./clientAddress";

describe("client address for watermarks and the audit log", () => {
  it("takes the address our ingress appended, not one the browser sent", () => {
    expect(clientAddress("6.6.6.6, 203.0.113.7", 1)).toBe("203.0.113.7");
  });

  it("skips the entries added by further trusted proxies", () => {
    expect(clientAddress("6.6.6.6, 203.0.113.7, 10.0.0.12", 2)).toBe("203.0.113.7");
  });

  it("uses the socket address Next.js fills in when there is no proxy", () => {
    expect(clientAddress("::1", 0)).toBe("::1");
  });

  it("returns nothing when the chain is shorter than the trusted proxies", () => {
    expect(clientAddress("203.0.113.7", 2)).toBeUndefined();
    expect(clientAddress(null, 1)).toBeUndefined();
    expect(clientAddress(" , ", 1)).toBeUndefined();
  });
});
