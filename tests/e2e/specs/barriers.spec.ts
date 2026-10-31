import { expect, test } from "@playwright/test";

import { as, hasSession, projectId } from "../support/session";
import { label } from "../support/i18n";

/**
 * Information barriers as the user sees them (PRD Bab 3): navigation per role, and direct URLs into areas the role
 * may not enter. The API and row-level security enforce the same rules underneath (backend integration tests).
 * These checks do not depend on the flow's progress; what each side sees before and after a release is checked in
 * mvp-flow.spec.ts, in order.
 */
test.skip(!projectId() || !hasSession("target") || !hasSession("client"), "needs the e2e fixture and users");

const nav = (page: import("@playwright/test").Page) => page.getByRole("menu");

test("the target works on requests, the data room and Q&A, never on findings or reports", async ({
  browser,
}) => {
  const page = await as(browser, "target");
  await page.goto(`/projects/${projectId()}/requests`);
  for (const item of ["nav.requests", "nav.dataRoom", "nav.qna"]) {
    await expect(nav(page).getByText(label(item, { exact: true }))).toBeVisible();
  }
  for (const item of ["nav.findings", "nav.reports"]) {
    await expect(nav(page).getByText(label(item, { exact: true }))).toHaveCount(0);
  }
  await page.goto(`/projects/${projectId()}/findings`);
  await expect(page.getByText(label("findings.newFinding"))).toHaveCount(0);
  await expect(page.getByText(label("findings.heatMap"))).toHaveCount(0);
});

test("the client follows findings, Q&A and reports, never the data room or request list", async ({
  browser,
}) => {
  const page = await as(browser, "client");
  await page.goto(`/projects/${projectId()}`);
  for (const item of ["nav.findings", "nav.qna", "nav.reports"]) {
    await expect(nav(page).getByText(label(item, { exact: true }))).toBeVisible();
  }
  for (const item of ["nav.requests", "nav.dataRoom"]) {
    await expect(nav(page).getByText(label(item, { exact: true }))).toHaveCount(0);
  }
  await page.goto(`/projects/${projectId()}/data-room`);
  await expect(page.getByText("01 Legal")).toHaveCount(0);
  await page.goto(`/projects/${projectId()}/findings`);
  await expect(page.getByText(label("findings.newFinding"))).toHaveCount(0); // read-only: released findings only
});
