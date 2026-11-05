import { describe, expect, it } from "vitest";

import { STEP_UP_REQUIRED, stepUpLoginUrl } from "./stepUp";

describe("step-up sign-in", () => {
  it("asks the login route for a fresh sign-in and comes back", () => {
    expect(stepUpLoginUrl("/projects/p1/reports?x=1")).toBe(
      "/auth/login?stepUp=1&returnTo=%2Fprojects%2Fp1%2Freports%3Fx%3D1",
    );
    expect(STEP_UP_REQUIRED).toBe("urn:diligence:problem:step-up-required");
  });
});
