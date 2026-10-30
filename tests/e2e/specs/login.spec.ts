import { expect, test } from "@playwright/test";

/**
 * Login → shell → navigation for an internal user without MFA (project manager created by
 * `make keycloak-bootstrap`). Requires the full stack (`make up-all`) and E2E_PM_USER / E2E_PM_PASSWORD.
 */
const user = process.env.E2E_PM_USER;
const password = process.env.E2E_PM_PASSWORD;

test.skip(!user || !password, "E2E_PM_USER and E2E_PM_PASSWORD are required");

test("project manager signs in and sees the consultant navigation", async ({ page }) => {
  await page.goto("/admin/users");
  await expect(page).toHaveURL(/\/realms\/diligence\/protocol\/openid-connect\/auth/);

  await page.locator("#username").fill(user!);
  await page.locator("#password").fill(password!);
  await page.locator("#kc-login").click();

  // Not an admin: the admin page redirects home; consultant areas are in the menu, administration is not.
  await expect(page).toHaveURL(/localhost:3000\/$/);
  const nav = page.getByRole("menu");
  for (const label of [/Request list/, /Data room/, /Temuan|Findings/]) {
    await expect(nav.getByText(label).first()).toBeVisible();
  }
  await expect(nav.getByText(/Administrasi|Administration/)).toHaveCount(0);
});
