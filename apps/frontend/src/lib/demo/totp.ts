import { createHmac } from "node:crypto";

/** RFC 6238 TOTP (HMAC-SHA1, 6 digits, 30-second steps — Keycloak's default policy) for a raw key. */
export function totp(key: Buffer, timeMs: number = Date.now(), period = 30, digits = 6): string {
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(timeMs / 1000 / period)));
  const hmac = createHmac("sha1", key).update(counter).digest();
  const offset = (hmac[hmac.length - 1] ?? 0) & 0x0f;
  const code = (hmac.readUInt32BE(offset) & 0x7fffffff) % 10 ** digits;
  return code.toString().padStart(digits, "0");
}

/**
 * The current code for a Keycloak OTP credential. Keycloak keys the HMAC with the stored secret's own bytes (an
 * authenticator app receives their base32 encoding in the QR code), as `tests/security/zap-login.py` does.
 */
export function keycloakTotp(secret: string, timeMs: number = Date.now()): string {
  return totp(Buffer.from(secret, "utf8"), timeMs);
}
