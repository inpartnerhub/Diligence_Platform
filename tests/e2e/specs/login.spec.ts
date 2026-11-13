import { expect, test } from "@playwright/test";

import { credentials, totp } from "../support/auth";

/**
 * Login → OTP → shell → navigation for an internal user (project manager created by `make keycloak-bootstrap`; every
 * role requires OTP, #161). Credentials: E2E_PM_USER / E2E_PM_PASSWORD / E2E_PM_TOTP_SECRET, or the KEYCLOAK_DEV_PM
 * and KEYCLOAK_DEV_TOTP_SECRET ones from .env.
 */
const pm = credentials("pm");

test.skip(!pm, "project manager credentials are required");

test("project manager signs in and sees the consultant navigation", async ({
  page,
}) => {
  await page.goto("/admin/users");
  await expect(page).toHaveURL(
    /\/realms\/diligence\/protocol\/openid-connect\/auth/,
  );

  await page.locator("#username").fill(pm!.username);
  await page.locator("#password").fill(pm!.password);
  await page.locator("#kc-login").click();
  await page.locator("#otp").fill(totp(pm!.otp!));
  await page.locator("#kc-login").click();

  // Not an admin: the admin page redirects home; consultant areas are in the menu, administration is not.
  await expect(page).toHaveURL(/localhost:3000\/$/);
  const nav = page.getByRole("menu");
  for (const label of [/Request list/, /Data room/, /Temuan|Findings/]) {
    await expect(nav.getByText(label).first()).toBeVisible();
  }
  await expect(nav.getByText(/Administrasi|Administration/)).toHaveCount(0);
});
