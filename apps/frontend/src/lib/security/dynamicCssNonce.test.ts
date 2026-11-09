import { describe, expect, it } from "vitest";

import { documentNonce, withNonce } from "./dynamicCssNonce";

describe("withNonce", () => {
  it("adds the document nonce when the caller passed none", () => {
    expect(withNonce({ prepend: true }, "abc")).toEqual({ prepend: true, csp: { nonce: "abc" } });
    expect(withNonce(undefined, "abc")).toEqual({ csp: { nonce: "abc" } });
  });

  it("keeps a nonce the caller passed (antd's ConfigProvider)", () => {
    const options = { csp: { nonce: "from-antd" } };
    expect(withNonce(options, "abc")).toBe(options);
  });

  it("changes nothing without a document nonce (development, tests)", () => {
    const options = { mark: "x" };
    expect(withNonce(options, undefined)).toBe(options);
  });
});

describe("documentNonce", () => {
  const doc = (element: Partial<HTMLElement> | null) => ({ querySelector: () => element }) as never;

  it("reads the nonce property, which browsers keep after hiding the attribute", () => {
    expect(documentNonce(doc({ nonce: "n1", getAttribute: () => "" }))).toBe("n1");
  });

  it("falls back to the attribute and to nothing", () => {
    expect(documentNonce(doc({ nonce: "", getAttribute: () => "n2" }))).toBe("n2");
    expect(documentNonce(doc(null))).toBeUndefined();
    expect(documentNonce(undefined)).toBeUndefined();
  });
});
