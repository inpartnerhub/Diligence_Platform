import { randomBytes } from "node:crypto";

import { describe, expect, it } from "vitest";

import { decryptSession, encryptSession, sessionKey } from "./session-crypto";

const current = sessionKey("CURRENT", randomBytes(32).toString("base64"));
const previous = sessionKey("PREVIOUS", randomBytes(32).toString("base64"));
const payload = JSON.stringify({ accessToken: "eyJ.access.token", refreshToken: "eyJ.refresh.token" });
const aad = "diligence:session:abc";

describe("session encryption (#172)", () => {
  it("round-trips and leaves no token readable in the record", () => {
    const record = encryptSession(payload, aad, current);

    expect(record).toMatch(/^enc1\.[\w-]+\.[\w-]+\.[\w-]+$/);
    expect(record).not.toContain("eyJ");
    expect(Buffer.from(record.split(".")[3] ?? "", "base64url").toString("utf8")).not.toContain("token");
    expect(decryptSession(record, aad, [current])).toBe(payload);
  });

  it("uses a fresh IV for every record", () => {
    expect(encryptSession(payload, aad, current)).not.toBe(encryptSession(payload, aad, current));
  });

  it("reads records of the previous key during a rotation", () => {
    const old = encryptSession(payload, aad, previous);

    expect(decryptSession(old, aad, [current, previous])).toBe(payload);
    expect(decryptSession(old, aad, [current])).toBeNull();
  });

  it("refuses a record moved to another session id", () => {
    expect(
      decryptSession(encryptSession(payload, aad, current), "diligence:session:other", [current]),
    ).toBeNull();
  });

  it("refuses tampered, truncated and plaintext records", () => {
    const [version, id, iv, sealed = ""] = encryptSession(payload, aad, current).split(".");
    const flipped = Buffer.from(sealed, "base64url");
    flipped.writeUInt8(flipped.readUInt8(0) ^ 1, 0);

    expect(
      decryptSession([version, id, iv, flipped.toString("base64url")].join("."), aad, [current]),
    ).toBeNull();
    expect(decryptSession([version, id, iv, "AAAA"].join("."), aad, [current])).toBeNull();
    expect(decryptSession([version, id, "AAAA", sealed].join("."), aad, [current])).toBeNull();
    expect(decryptSession(payload, aad, [current])).toBeNull();
  });

  it("derives a stable key id and rejects keys that are not 32 bytes", () => {
    const value = randomBytes(32).toString("base64");

    expect(sessionKey("A", value).id).toBe(sessionKey("B", value).id);
    expect(sessionKey("A", value).id).not.toBe(current.id);
    expect(() => sessionKey("SESSION_ENCRYPTION_KEY", randomBytes(16).toString("base64"))).toThrow(
      /SESSION_ENCRYPTION_KEY must be 32 bytes/,
    );
  });
});
