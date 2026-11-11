import { afterEach, describe, expect, it, vi } from "vitest";

import { logError, logWarn } from "./log";

afterEach(() => vi.restoreAllMocks());

describe("server log lines", () => {
  it("writes one JSON line, so a forged line break stays inside the message", () => {
    const spy = vi.spyOn(console, "error").mockImplementation(() => undefined);

    logError("Update webhook failed", new Error('id 1\n{"log.level":"info","message":"forged"}'));

    expect(spy).toHaveBeenCalledTimes(1);
    const line = spy.mock.calls[0]?.[0] as string;
    expect(line).not.toContain("\n");
    const entry = JSON.parse(line);
    expect(entry["log.level"]).toBe("error");
    expect(entry.message).toBe("Update webhook failed");
    expect(entry.error.type).toBe("Error");
    expect(entry.error.message).toContain("forged");
  });

  it("logs warnings and non-Error values", () => {
    const spy = vi.spyOn(console, "warn").mockImplementation(() => undefined);

    logWarn("Redis rate limiter client error", "ECONNREFUSED\r\nx");

    const entry = JSON.parse(spy.mock.calls[0]?.[0] as string);
    expect(entry["log.level"]).toBe("warn");
    expect(entry.error).toEqual({ message: "ECONNREFUSED\r\nx" });
  });

  it("omits the error field when there is none", () => {
    const spy = vi.spyOn(console, "error").mockImplementation(() => undefined);

    logError("Something happened");

    expect(JSON.parse(spy.mock.calls[0]?.[0] as string)).not.toHaveProperty("error");
  });
});
