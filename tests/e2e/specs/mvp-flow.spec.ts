import { fileURLToPath } from "node:url";

import { expect, type Page, test } from "@playwright/test";

import { ROLES } from "../support/auth";
import { label } from "../support/i18n";
import { as, hasSession, projectId } from "../support/session";

/**
 * One due diligence engagement end to end, each step by the role that does it in practice (PRD Bab 5 and 11):
 * request list → delivery by the target → finding with a cited source → four-eyes review and release → Q&A through
 * approval → report issued to the client. Runs in order on project E2E-001 (tests/e2e/fixtures/seed.sh).
 */
test.describe.configure({ mode: "serial" });
test.skip(!projectId() || !ROLES.every(hasSession), "needs the e2e fixture and every e2e user");

const DOCUMENT = fileURLToPath(new URL("../fixtures/deed-of-establishment.pdf", import.meta.url));
const FINDING = "Shareholder approval for the 2025 capital increase is missing";
const QUESTION = "Please provide the shareholder resolution approving the 2025 capital increase.";

const project = (path = "") => `/projects/${projectId()}${path}`;
const drawer = (page: Page) => page.locator(".ant-drawer");
const modal = (page: Page) => page.locator(".ant-modal-container");
const toast = (page: Page, key: string, timeout?: number) =>
  expect(page.locator(".ant-message").getByText(label(key))).toBeVisible({ timeout });

/** A button in the open drawer by its visible text (the drawer's close icon is labelled "Close" too). */
const action = (page: Page, key: string) =>
  drawer(page)
    .locator("button")
    .filter({ hasText: label(key, { exact: true }) });

/** Picks a folder in a tree select inside the drawer (data-room folder pickers). */
async function chooseFolder(page: Page, placeholderKey: string, folder: string) {
  await drawer(page)
    .locator(".ant-select")
    .filter({ hasText: label(placeholderKey) })
    .click();
  await page.locator(".ant-select-tree-title").filter({ hasText: folder }).click();
}

test("1 · the project manager releases the request list to the target", async ({ browser }) => {
  const target = await as(browser, "target");
  await target.goto(project("/requests"));
  await expect(target.getByRole("row", { name: /LEG-001/ })).toHaveCount(0); // unreleased items stay internal

  const page = await as(browser, "pm");
  await page.goto(project("/requests"));
  await page.getByRole("button", { name: label("requests.release", { exact: true }) }).click();
  await modal(page).locator(".ant-btn-primary").click();
  await expect(
    page.getByRole("row", { name: /LEG-001/ }).getByText(label("requests.statusValues.REQUESTED")),
  ).toBeVisible();
});

test("2 · the target uploads the deed and reports the item delivered", async ({ browser }) => {
  const page = await as(browser, "target");
  await page.goto(project("/requests"));
  await page.getByRole("row", { name: /LEG-001/ }).click();
  await chooseFolder(page, "documentSource.chooseFolder", "01 Legal");
  await drawer(page).locator("input[type=file]").setInputFiles(DOCUMENT);
  await toast(page, "requests.linked");
  await action(page, "requests.submit.RECEIVED").click();
  await expect(
    drawer(page).getByText(label("requests.statusValues.RECEIVED"), { exact: false }).first(),
  ).toBeVisible();
});

test("3 · the analyst records a rated finding citing the delivered deed and submits it", async ({
  browser,
}) => {
  const page = await as(browser, "analyst");
  await page.goto(project("/findings"));
  await page.getByRole("button", { name: label("findings.newFinding") }).click();
  await modal(page).locator("#category").fill("Corporate");
  await modal(page).locator("#title").fill(FINDING);
  await modal(page)
    .locator("#description")
    .fill("The deed shows the increase but no GMS resolution approves it.");
  await modal(page).locator("#impact").fill("4");
  await modal(page).locator("#likelihood").fill("4");
  await modal(page).locator(".ant-btn-primary").click();
  await toast(page, "findings.created");

  // The document becomes citable once the malware scan has passed; the folder's list is loaded when it is chosen, so
  // each attempt opens the finding afresh.
  await expect(async () => {
    await page.goto(project("/findings"));
    await page.getByRole("row", { name: new RegExp(FINDING) }).click();
    await chooseFolder(page, "findings.chooseFolder", "01 Legal");
    await drawer(page)
      .locator(".ant-select")
      .filter({ hasText: label("findings.chooseDocument") })
      .click();
    await page.locator(".ant-select-item-option").filter({ hasText: "deed-of-establishment.pdf" }).click({
      timeout: 3_000,
    });
  }).toPass({ timeout: 60_000 });
  await drawer(page).getByPlaceholder(label("findings.pageReference")).fill("1");
  await action(page, "findings.link").click();
  await toast(page, "findings.linked");
  await action(page, "findings.actions.IN_REVIEW").click();
  await expect(drawer(page).getByText(label("findings.statusValues.IN_REVIEW")).first()).toBeVisible();
});

test("4 · the project manager approves it (four eyes)", async ({ browser }) => {
  const page = await as(browser, "pm");
  await page.goto(project("/findings"));
  await page.getByRole("row", { name: new RegExp(FINDING) }).click();
  await action(page, "findings.actions.APPROVED").click();
  await expect(drawer(page).getByText(label("findings.statusValues.APPROVED")).first()).toBeVisible();

  const client = await as(browser, "client");
  await client.goto(project("/findings"));
  await expect(client.getByText(label("findings.emptyClient"))).toBeVisible(); // approved is not released
});

test("5 · only a partner releases it to the client", async ({ browser }) => {
  const page = await as(browser, "partner");
  await page.goto(project("/findings"));
  await page.getByRole("row", { name: new RegExp(FINDING) }).click();
  await action(page, "findings.actions.RELEASED").click();
  await expect(drawer(page).getByText(label("findings.statusValues.RELEASED")).first()).toBeVisible();
});

test("6 · the client now sees the released finding", async ({ browser }) => {
  const page = await as(browser, "client");
  await page.goto(project("/findings"));
  await expect(page.getByRole("row", { name: new RegExp(FINDING) })).toBeVisible();
});

test("7 · the analyst's question waits for approval before the target sees it", async ({ browser }) => {
  const analyst = await as(browser, "analyst");
  await analyst.goto(project("/qna"));
  await analyst.getByRole("button", { name: label("qna.newQuestion") }).click();
  await modal(analyst).locator("#body").fill(QUESTION);
  await modal(analyst)
    .getByRole("button", { name: label("qna.submitForApproval", { exact: true }) })
    .click();
  await toast(analyst, "qna.created");

  const target = await as(browser, "target");
  await target.goto(project("/qna"));
  await expect(target.getByText(QUESTION)).toHaveCount(0);
});

test("8 · the project manager approves and sends it", async ({ browser }) => {
  const page = await as(browser, "pm");
  await page.goto(project("/qna"));
  await page.getByRole("row", { name: new RegExp(QUESTION.slice(0, 40)) }).click();
  await action(page, "qna.moves.SENT").click();
  await expect(drawer(page).getByText(label("qna.statusValues.SENT")).first()).toBeVisible();
});

test("9 · the target answers and uploads the resolution from the answer panel", async ({ browser }) => {
  const page = await as(browser, "target");
  await page.goto(project("/qna"));
  await page.getByRole("row", { name: new RegExp(QUESTION.slice(0, 40)) }).click();
  await drawer(page)
    .getByPlaceholder(label("qna.answerPlaceholder"))
    .fill("Attached: the deed; the resolution follows.");
  await action(page, "qna.sendAnswer").click();
  await toast(page, "qna.answered");
  await chooseFolder(page, "documentSource.chooseFolder", "01 Legal");
  await drawer(page).locator("input[type=file]").setInputFiles(DOCUMENT);
  await expect(drawer(page).getByText("deed-of-establishment.pdf")).toBeVisible();
});

test("10 · the project manager closes the question", async ({ browser }) => {
  const page = await as(browser, "pm");
  await page.goto(project("/qna"));
  await page.getByRole("row", { name: new RegExp(QUESTION.slice(0, 40)) }).click();
  await action(page, "qna.moves.CLOSED").click();
  await expect(drawer(page).getByText(label("qna.statusValues.CLOSED")).first()).toBeVisible();
});

test("11 · a partner generates the report from the released finding and issues it", async ({ browser }) => {
  const page = await as(browser, "partner");
  await page.goto(project("/reports"));
  await page.getByRole("button", { name: label("reports.generate", { exact: true }) }).click();
  await modal(page).locator(".ant-btn-primary").click();
  await toast(page, "reports.generated", 60_000); // Word, then PDF through Gotenberg
  await page
    .getByRole("button", { name: label("reports.issue", { exact: true }) })
    .first()
    .click();
  await page.locator(".ant-popconfirm .ant-btn-primary").click();
  await toast(page, "reports.issuedMessage");
});

test("12 · the client downloads the issued report as a watermarked PDF", async ({ browser }) => {
  const page = await as(browser, "client");
  await page.goto(project("/reports"));
  const link = page.getByRole("link", { name: /PDF/ }).first();
  await expect(link).toBeVisible();
  const response = await page.request.get((await link.getAttribute("href"))!);
  expect(response.status()).toBe(200);
  expect(response.headers()["content-type"]).toBe("application/pdf");
  expect((await response.body()).subarray(0, 5).toString()).toBe("%PDF-");
});
