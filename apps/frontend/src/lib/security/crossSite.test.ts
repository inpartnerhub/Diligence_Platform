import { describe, expect, it } from "vitest";

import { isCrossSiteWrite } from "./crossSite";

const APP = "https://app.example.com";
const headers = (values: Record<string, string>) => new Headers(values);

describe("cross-site write detection", () => {
  it("never blocks safe methods", () => {
    expect(
      isCrossSiteWrite("GET", headers({ "sec-fetch-site": "cross-site", origin: "https://evil.test" }), APP),
    ).toBe(false);
  });

  it("blocks writes the browser marks as cross-site", () => {
    expect(isCrossSiteWrite("POST", headers({ "sec-fetch-site": "cross-site" }), APP)).toBe(true);
  });

  it("blocks writes from another origin, a sibling subdomain or an opaque origin", () => {
    expect(isCrossSiteWrite("POST", headers({ origin: "https://evil.test" }), APP)).toBe(true);
    expect(
      isCrossSiteWrite(
        "POST",
        headers({ "sec-fetch-site": "same-site", origin: "https://x.example.com" }),
        APP,
      ),
    ).toBe(true);
    expect(isCrossSiteWrite("POST", headers({ origin: "null" }), APP)).toBe(true);
  });

  it("allows same-origin writes and clients that send no browser headers", () => {
    expect(isCrossSiteWrite("POST", headers({ "sec-fetch-site": "same-origin", origin: APP }), APP)).toBe(
      false,
    );
    expect(isCrossSiteWrite("DELETE", headers({}), APP)).toBe(false);
  });

  it("falls back to Fetch Metadata only when the app origin is not configured", () => {
    expect(isCrossSiteWrite("POST", headers({ origin: "https://evil.test" }), undefined)).toBe(false);
    expect(isCrossSiteWrite("POST", headers({ "sec-fetch-site": "cross-site" }), "not a url")).toBe(true);
  });
});
