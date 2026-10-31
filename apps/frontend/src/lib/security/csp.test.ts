import { describe, expect, it } from "vitest";

import { contentSecurityPolicy } from "./csp";

const directives = (policy: string) =>
  Object.fromEntries(
    policy.split("; ").map((directive) => {
      const [name, ...values] = directive.split(" ");
      return [name, values.join(" ")];
    }),
  );

describe("content security policy", () => {
  it("runs only scripts carrying this request's nonce", () => {
    const policy = directives(contentSecurityPolicy({ nonce: "abc", development: false }));
    expect(policy["script-src"]).toBe("'self' 'nonce-abc' 'strict-dynamic'");
    expect(policy["object-src"]).toBe("'none'");
    expect(policy["base-uri"]).toBe("'none'");
    expect(policy["frame-ancestors"]).toBe("'none'");
  });

  it("allows eval in development only", () => {
    expect(contentSecurityPolicy({ nonce: "n", development: true })).toContain("'unsafe-eval'");
    expect(contentSecurityPolicy({ nonce: "n", development: false })).not.toContain("'unsafe-eval'");
  });

  it("lets the logout form reach Keycloak and errors reach Sentry, by origin only", () => {
    const policy = directives(
      contentSecurityPolicy({
        nonce: "n",
        development: false,
        issuer: "https://auth.example.com/realms/diligence",
        sentryDsn: "https://key@o1.ingest.sentry.io/42",
      }),
    );
    expect(policy["form-action"]).toBe("'self' https://auth.example.com");
    expect(policy["connect-src"]).toBe("'self' https://o1.ingest.sentry.io");
  });

  it("ignores missing or malformed origins", () => {
    const policy = directives(
      contentSecurityPolicy({ nonce: "n", development: false, sentryDsn: "not a url" }),
    );
    expect(policy["connect-src"]).toBe("'self'");
    expect(policy["form-action"]).toBe("'self'");
  });
});
