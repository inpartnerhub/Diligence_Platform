import type { Browser, Page } from "@playwright/test";

import { type RoleName, statePath } from "./auth";

/** The project the flow specs work in (tests/e2e/fixtures/seed.sh, or E2E_PROJECT_ID for a prepared one). */
export const projectId = () => process.env.E2E_PROJECT_ID;

/** A page signed in as the role, in its own browser context (like a separate person on another machine). */
export async function as(browser: Browser, role: RoleName): Promise<Page> {
  const context = await browser.newContext({ storageState: statePath(role) });
  return context.newPage();
}
