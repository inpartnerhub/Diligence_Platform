import { describe, expect, it } from "vitest";

import { createLimiter, retryDelayMs } from "./uploadQueue";

describe("createLimiter", () => {
  it("never runs more than the limit at once and runs every task in order", async () => {
    const run = createLimiter(3);
    let active = 0;
    let peak = 0;
    const started: number[] = [];
    const tasks = Array.from({ length: 10 }, (_, i) =>
      run(async () => {
        started.push(i);
        active++;
        peak = Math.max(peak, active);
        await new Promise((resolve) => setTimeout(resolve, 5));
        active--;
        return i;
      }),
    );

    await expect(Promise.all(tasks)).resolves.toEqual([0, 1, 2, 3, 4, 5, 6, 7, 8, 9]);
    expect(peak).toBe(3);
    expect(started).toEqual([0, 1, 2, 3, 4, 5, 6, 7, 8, 9]);
  });

  it("frees the slot when a task fails", async () => {
    const run = createLimiter(1);
    await expect(run(() => Promise.reject(new Error("boom")))).rejects.toThrow("boom");
    await expect(run(() => Promise.resolve("next"))).resolves.toBe("next");
  });
});

describe("retryDelayMs", () => {
  it("follows Retry-After, capped at a minute", () => {
    expect(retryDelayMs("5", 0)).toBe(5000);
    expect(retryDelayMs("600", 0)).toBe(60_000);
  });

  it("backs off without a usable Retry-After", () => {
    expect(retryDelayMs(null, 0)).toBe(1000);
    expect(retryDelayMs("soon", 3)).toBe(8000);
    expect(retryDelayMs(null, 10)).toBe(30_000);
  });
});
