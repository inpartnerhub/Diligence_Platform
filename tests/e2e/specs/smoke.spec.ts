import { expect, test } from "@playwright/test";

test("frontend health endpoint responds", async ({ request }) => {
  const response = await request.get("/api/health");
  expect(response.ok()).toBeTruthy();
  expect(await response.json()).toEqual({ status: "UP" });
});

test("home page requires sign-in", async ({ page }) => {
  await page.goto("/");
  await expect(page).toHaveURL(/openid-connect\/auth|\/auth\/login/); // protected: redirects to sign-in
});
