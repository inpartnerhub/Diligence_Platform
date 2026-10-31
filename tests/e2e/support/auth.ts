import { createHmac } from "node:crypto";

import type { Page } from "@playwright/test";

/** The roles the suite signs in as. The PM is the bootstrap's local manager; the rest are the e2e users. */
export const ROLES = ["pm", "partner", "analyst", "target", "client"] as const;
export type RoleName = (typeof ROLES)[number];

export const statePath = (role: RoleName) => `.auth/${role}.json`;

const env = (...names: string[]) => names.map((n) => process.env[n]).find(Boolean);

export function credentials(
  role: RoleName,
): { username: string; password: string; otp?: string } | undefined {
  if (role === "pm") {
    const username = env("E2E_PM_USER", "KEYCLOAK_DEV_PM");
    const password = env("E2E_PM_PASSWORD", "KEYCLOAK_DEV_PM_PASSWORD");
    return username && password ? { username, password } : undefined;
  }
  const password = env("E2E_PASSWORD", "KEYCLOAK_E2E_PASSWORD");
  const otp = env("E2E_TOTP_SECRET", "KEYCLOAK_E2E_TOTP_SECRET");
  if (!password || !otp) return undefined;
  return { username: `e2e.${role}`, password, otp: role === "target" || role === "client" ? otp : undefined };
}

/**
 * RFC 6238 code (SHA-1, 6 digits, 30 s), as configured in the realm's OTP policy. Keycloak keys the HMAC with the
 * stored secret's characters as they are (base32 is only how it shows the secret in the QR code).
 */
export function totp(secret: string, at = Date.now()): string {
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(at / 30_000)));
  const hmac = createHmac("sha1", Buffer.from(secret, "utf8")).update(counter).digest();
  const offset = hmac[hmac.length - 1]! & 0x0f;
  const code = (hmac.readUInt32BE(offset) & 0x7fffffff) % 1_000_000;
  return code.toString().padStart(6, "0");
}

/** Keycloak sign-in through the app: password, then the OTP form for roles that require it. */
export async function signIn(page: Page, role: RoleName): Promise<void> {
  const user = credentials(role);
  if (!user) throw new Error(`No credentials for ${role}`);
  await page.goto("/");
  await page.locator("#username").fill(user.username);
  await page.locator("#password").fill(user.password);
  await page.locator("#kc-login").click();
  if (user.otp) {
    await page.locator("#otp").fill(totp(user.otp));
    await page.locator("#kc-login").click();
    // Keycloak refuses a code that was already used (a rerun within 30 s): wait for the next one and retry once.
    await page.waitForLoadState();
    if (await page.locator("#otp").isVisible()) {
      await page.waitForTimeout(30_000 - (Date.now() % 30_000) + 1_000);
      await page.locator("#otp").fill(totp(user.otp));
      await page.locator("#kc-login").click();
    }
  }
  const app = new URL(process.env.E2E_BASE_URL ?? "http://localhost:3000").origin;
  await page.waitForURL((url) => url.origin === app && !url.pathname.startsWith("/auth/"));
}
