import { createCipheriv, createDecipheriv, createHash, randomBytes } from "node:crypto";

/**
 * Encryption of session records at rest in Redis (#172): AES-256-GCM with a key only the BFF holds. A record is
 * `enc1.<key id>.<iv>.<ciphertext + tag>` (base64url); the key id is derived from the key, so a record written with the
 * previous key can still be read while a rotation is in progress. The Redis key is bound as additional authenticated
 * data, so a record cannot be moved to another session id.
 */
const VERSION = "enc1";
const IV_BYTES = 12;
const TAG_BYTES = 16;

export interface SessionKey {
  id: string;
  key: Buffer;
}

/** Parses a base64 AES-256 key (32 bytes); `name` is only used in the error message. */
export function sessionKey(name: string, value: string): SessionKey {
  const key = Buffer.from(value.trim(), "base64");
  if (key.length !== 32) {
    throw new Error(`${name} must be 32 bytes, base64-encoded (openssl rand -base64 32)`);
  }
  const id = createHash("sha256").update("diligence-session-key-id\0").update(key).digest().subarray(0, 6);
  return { id: id.toString("base64url"), key };
}

export function encryptSession(plaintext: string, aad: string, key: SessionKey): string {
  const iv = randomBytes(IV_BYTES);
  const cipher = createCipheriv("aes-256-gcm", key.key, iv, { authTagLength: TAG_BYTES });
  cipher.setAAD(Buffer.from(aad, "utf8"));
  const sealed = Buffer.concat([cipher.update(plaintext, "utf8"), cipher.final(), cipher.getAuthTag()]);
  return [VERSION, key.id, iv.toString("base64url"), sealed.toString("base64url")].join(".");
}

/**
 * Decrypts a record written by {@link encryptSession} with one of `keys`. Returns null for anything else — plaintext
 * records from before #172, an unknown (retired) key, or a record that fails authentication — so the caller treats it
 * as no session and the user signs in again.
 */
export function decryptSession(record: string, aad: string, keys: readonly SessionKey[]): string | null {
  const [version, keyId, ivText, sealedText, ...rest] = record.split(".");
  if (version !== VERSION || ivText === undefined || sealedText === undefined || rest.length > 0) {
    return null;
  }
  const key = keys.find((candidate) => candidate.id === keyId);
  if (!key) {
    return null;
  }
  const iv = Buffer.from(ivText, "base64url");
  const sealed = Buffer.from(sealedText, "base64url");
  if (iv.length !== IV_BYTES || sealed.length < TAG_BYTES) {
    return null;
  }
  try {
    const decipher = createDecipheriv("aes-256-gcm", key.key, iv, { authTagLength: TAG_BYTES });
    decipher.setAAD(Buffer.from(aad, "utf8"));
    decipher.setAuthTag(sealed.subarray(sealed.length - TAG_BYTES));
    return Buffer.concat([
      decipher.update(sealed.subarray(0, sealed.length - TAG_BYTES)),
      decipher.final(),
    ]).toString("utf8");
  } catch {
    return null;
  }
}
