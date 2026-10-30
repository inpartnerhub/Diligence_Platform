import "server-only";

import { createClient } from "redis";

import { serverEnv } from "@/lib/config/env";

import type { Role } from "./roles";

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
  user: SessionUser;
}

const KEY_PREFIX = "diligence:session:";

function connect() {
  return createClient({ url: serverEnv.redisUrl })
    .on("error", (error: unknown) => console.error("Redis session store error", error))
    .connect();
}

type Redis = Awaited<ReturnType<typeof connect>>;
const globalForRedis = globalThis as unknown as { diligenceRedis?: Promise<Redis> };

/** One connection per server process (survives dev hot reloads via globalThis). */
function redis(): Promise<Redis> {
  const client = (globalForRedis.diligenceRedis ??= connect());
  return client;
}

export async function readSession(sessionId: string): Promise<SessionData | null> {
  const raw = await (await redis()).get(KEY_PREFIX + sessionId);
  return raw ? (JSON.parse(raw) as SessionData) : null;
}

export async function writeSession(sessionId: string, data: SessionData, ttlSeconds: number): Promise<void> {
  await (await redis()).set(KEY_PREFIX + sessionId, JSON.stringify(data), { EX: Math.max(ttlSeconds, 60) });
}

/** Keeps the current TTL (bounded by the refresh token lifetime set at login). */
export async function updateSession(sessionId: string, data: SessionData): Promise<void> {
  await (await redis()).set(KEY_PREFIX + sessionId, JSON.stringify(data), { KEEPTTL: true });
}

export async function deleteSession(sessionId: string): Promise<void> {
  await (await redis()).del(KEY_PREFIX + sessionId);
}
