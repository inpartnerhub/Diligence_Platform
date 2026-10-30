import { describe, expect, it } from "vitest";

import { accessibleNav, canAccess } from "./permissions";
import { decodeJwtPayload, rolesFromRealmAccess } from "./roles";

describe("navigation access (PRD Bab 3)", () => {
  it("never shows findings or reports to the target side", () => {
    expect(canAccess(["TARGET"], "findings")).toBe(false);
    expect(canAccess(["TARGET"], "reports")).toBe(false);
    expect(accessibleNav(["TARGET"])).toEqual(["projects", "requests", "dataRoom", "qna"]);
  });

  it("keeps the client out of the data room and request list", () => {
    expect(accessibleNav(["CLIENT"])).toEqual(["projects", "dashboard", "findings", "qna", "reports"]);
  });

  it("limits administration to super admins", () => {
    expect(canAccess(["SUPER_ADMIN"], "admin")).toBe(true);
    expect(canAccess(["PARTNER", "PROJECT_MANAGER"], "admin")).toBe(false);
  });

  it("gives the internal team every project area", () => {
    expect(accessibleNav(["ANALYST"])).toEqual([
      "projects",
      "dashboard",
      "requests",
      "dataRoom",
      "findings",
      "qna",
      "reports",
    ]);
  });

  it("grants nothing without roles", () => {
    expect(accessibleNav([])).toEqual([]);
  });
});

describe("token role extraction", () => {
  it("maps Keycloak realm roles and ignores technical roles", () => {
    expect(
      rolesFromRealmAccess({ roles: ["analyst", "offline_access", "mfa_required", "super_admin"] }),
    ).toEqual(["ANALYST", "SUPER_ADMIN"]);
    expect(rolesFromRealmAccess(undefined)).toEqual([]);
    expect(rolesFromRealmAccess({ roles: "analyst" })).toEqual([]);
  });

  it("decodes a JWT payload", () => {
    const payload = Buffer.from(JSON.stringify({ sub: "abc" })).toString("base64url");
    expect(decodeJwtPayload(`h.${payload}.s`)).toEqual({ sub: "abc" });
    expect(decodeJwtPayload("invalid")).toEqual({});
  });
});
