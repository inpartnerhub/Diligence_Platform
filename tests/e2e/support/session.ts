import net from "node:net";
import type { Browser, Page } from "@playwright/test";

import { type RoleName, statePath } from "./auth";

/** The project the flow specs work in (tests/e2e/fixtures/seed.sh, or E2E_PROJECT_ID for a prepared one). */
export const projectId = () => process.env.E2E_PROJECT_ID;

/** Flushes Redis rate limiting store so automated rapid tests do not trigger HTTP 429. */
export async function resetRateLimits(): Promise<void> {
  const redisAuth = process.env.REDIS_PASSWORD;
  return new Promise((resolve) => {
    const socket = net.createConnection({ host: "127.0.0.1", port: 6379 }, () => {
      const authCmd = redisAuth ? `AUTH ${redisAuth}\r\n` : "";
      socket.write(`${authCmd}SELECT 1\r\nFLUSHDB\r\nQUIT\r\n`);
    });
    socket.on("data", () => {});
    socket.on("close", () => resolve());
    socket.on("error", () => resolve());
  });
}

/** A page signed in as the role, in its own browser context (like a separate person on another machine). */
export async function as(browser: Browser, role: RoleName): Promise<Page> {
  const context = await browser.newContext({ storageState: statePath(role) });
  return context.newPage();
}
