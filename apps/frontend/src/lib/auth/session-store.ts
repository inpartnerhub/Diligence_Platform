import "server-only";

import { createClient } from "redis";

import { serverEnv } from "@/lib/config/env";
import { logError } from "@/lib/log";

import type { Role } from "./roles";
import { decryptSession, encryptSession, type SessionKey, sessionKey } from "./session-crypto";

export interface SessionUser {
  id: string;
  name: string;
  email: string;
  roles: Role[];
}

/** Server-side session. Tokens never reach the browser; the cookie only carries an opaque, sealed session id. */
export interface SessionData {
  accessToken: string;
  refreshToken?: string;
  idToken?: string;
  /** Access token expiry, epoch seconds. */
  expiresAt: number;
  /** Sign-in time, epoch seconds; bounds the session's lifetime (#174). Missing in sessions created before. */
  createdAt?: number;
  user: SessionUser;
}

const KEY_PREFIX = "diligence:session:";

function connect() {
  return createClient({ url: serverEnv.redisUrl })
    .on("error", (error: unknown) => logError("Redis session store error", error))
    .connect();
}

type Redis = Awaited<ReturnType<typeof connect>>;
const globalForRedis = globalThis as unknown as { diligenceRedis?: Promise<Redis> };

/** One connection per server process (survives dev hot reloads via globalThis). */
function redis(): Promise<Redis> {
  const client = (globalForRedis.diligenceRedis ??= connect());
  return client;
}

/** Current key first (used for writing), then the previous one while a rotation is in progress (#172). */
function sessionKeys(): [SessionKey, ...SessionKey[]] {
  const current = sessionKey("SESSION_ENCRYPTION_KEY", serverEnv.sessionEncryptionKey);
  const previous = serverEnv.sessionEncryptionKeyPrevious;
  return previous ? [current, sessionKey("SESSION_ENCRYPTION_KEY_PREVIOUS", previous)] : [current];
}

/** Session records are encrypted in Redis; one that cannot be decrypted counts as no session (#172). */
export async function readSession(sessionId: string): Promise<SessionData | null> {
  const key = KEY_PREFIX + sessionId;
  const raw = await (await redis()).get(key);
  const json = raw ? decryptSession(raw, key, sessionKeys()) : null;
  return json ? (JSON.parse(json) as SessionData) : null;
}

async function storeSession(sessionId: string, data: SessionData, ttlSeconds: number): Promise<void> {
  const key = KEY_PREFIX + sessionId;
  const record = encryptSession(JSON.stringify(data), key, sessionKeys()[0]);
  await (await redis()).set(key, record, { EX: ttlSeconds });
}

export async function writeSession(sessionId: string, data: SessionData, ttlSeconds: number): Promise<void> {
  await storeSession(sessionId, data, Math.max(ttlSeconds, 60));
}

/** Replaces the session and sets its lifetime to that of the latest refresh token (#174). */
export async function updateSession(sessionId: string, data: SessionData, ttlSeconds: number): Promise<void> {
  await storeSession(sessionId, data, Math.max(ttlSeconds, 1));
}

export async function deleteSession(sessionId: string): Promise<void> {
  await (await redis()).del(KEY_PREFIX + sessionId);
}
