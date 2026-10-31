import { defineConfig, devices } from "@playwright/test";

/**
 * Black-box tests against a running stack or a deployed environment (E2E_BASE_URL). The setup project signs in once
 * per role; the flow specs reuse those sessions. See README.md.
 */
export default defineConfig({
  testDir: "./specs",
  globalSetup: "./global-setup.ts",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: process.env.CI ? [["github"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3000",
    // E2E_CHANNEL=chrome uses an installed Google Chrome instead of `npx playwright install chromium`.
    ...(process.env.E2E_CHANNEL && { channel: process.env.E2E_CHANNEL }),
    locale: "en-US",
    trace: "retain-on-failure",
  },
  projects: [
    { name: "setup", testMatch: /auth\.setup\.ts/ },
    {
      name: "chromium",
      use: {
        ...devices["Desktop Chrome"],
        ...(process.env.E2E_CHANNEL && { channel: process.env.E2E_CHANNEL }),
      },
      dependencies: ["setup"],
      testIgnore: /auth\.setup\.ts/,
    },
  ],
});
