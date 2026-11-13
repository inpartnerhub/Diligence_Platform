import { createClient } from "redis";

import { clientAddress } from "../api/clientAddress";
import { logWarn } from "@/lib/log";

export interface RateLimitOptions {
  windowSeconds: number;
  maxRequests: number;
}

export interface RateLimitResult {
  allowed: boolean;
  limit: number;
  remaining: number;
  retryAfter: number;
}

export interface RateLimitStore {
  consume(key: string, options: RateLimitOptions): Promise<RateLimitResult>;
}

/** In-memory sliding-window store for tests, standalone mode, and fallback. */
export class MemoryRateLimitStore implements RateLimitStore {
  private readonly hits = new Map<string, number[]>();

  async consume(key: string, options: RateLimitOptions): Promise<RateLimitResult> {
    const now = Date.now();
    const windowMs = options.windowSeconds * 1000;
    const windowStart = now - windowMs;

    const timestamps = (this.hits.get(key) ?? []).filter((ts) => ts > windowStart);

    if (timestamps.length >= options.maxRequests) {
      const oldest = timestamps[0] ?? now;
      const retryAfter = Math.max(1, Math.ceil((oldest + windowMs - now) / 1000));
      this.hits.set(key, timestamps);
      return {
        allowed: false,
        limit: options.maxRequests,
        remaining: 0,
        retryAfter,
      };
    }

    timestamps.push(now);
    this.hits.set(key, timestamps);

    return {
      allowed: true,
      limit: options.maxRequests,
      remaining: options.maxRequests - timestamps.length,
      retryAfter: options.windowSeconds,
    };
  }

  clear(): void {
    this.hits.clear();
  }
}

export interface RedisClientLike {
  eval(script: string, options: { keys: string[]; arguments: string[] }): Promise<unknown>;
}

const REDIS_SLIDING_WINDOW_LUA = `
local key = KEYS[1]
local now = tonumber(ARGV[1])
local windowMs = tonumber(ARGV[2])
local maxRequests = tonumber(ARGV[3])
local windowSeconds = tonumber(ARGV[4])
local member = ARGV[5]

local windowStart = now - windowMs
redis.call('ZREMRANGEBYSCORE', key, '-inf', windowStart)
local currentCount = redis.call('ZCARD', key)

if currentCount < maxRequests then
    redis.call('ZADD', key, now, member)
    redis.call('EXPIRE', key, windowSeconds)
    return { 1, maxRequests - currentCount - 1, windowSeconds }
else
    local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
    local oldestScore = now
    if oldest and #oldest >= 2 then
        oldestScore = tonumber(oldest[2])
    end
    local retryAfter = math.max(1, math.ceil((oldestScore + windowMs - now) / 1000))
    return { 0, 0, retryAfter }
end
`;

/** Distributed atomic sliding-window store backed by Redis sorted sets. */
export class RedisRateLimitStore implements RateLimitStore {
  constructor(private readonly client: RedisClientLike) {}

  async consume(key: string, options: RateLimitOptions): Promise<RateLimitResult> {
    const now = Date.now();
    const windowMs = options.windowSeconds * 1000;
    const redisKey = `diligence:web:ratelimit:${key}`; // the web app's Redis user owns diligence:web:* (#171)
    const member = `${now}:${Math.random().toString(36).slice(2, 8)}`;

    const res = (await this.client.eval(REDIS_SLIDING_WINDOW_LUA, {
      keys: [redisKey],
      arguments: [
        String(now),
        String(windowMs),
        String(options.maxRequests),
        String(options.windowSeconds),
        member,
      ],
    })) as [number, number, number];

    const allowed = res[0] === 1;
    const remaining = Number(res[1]);
    const retryAfter = Number(res[2]);

    return {
      allowed,
      limit: options.maxRequests,
      remaining,
      retryAfter,
    };
  }
}

const memoryStore = new MemoryRateLimitStore();
let redisStorePromise: Promise<RateLimitStore> | null = null;

function getStore(): Promise<RateLimitStore> | RateLimitStore {
  const redisUrl = process.env.REDIS_URL;
  if (!redisUrl) {
    return memoryStore;
  }
  if (!redisStorePromise) {
    redisStorePromise = (async () => {
      try {
        const client = createClient({ url: redisUrl });
        client.on("error", (err: unknown) => {
          logWarn("Redis rate limiter client error", err);
        });
        await client.connect();
        return new RedisRateLimitStore(client as unknown as RedisClientLike);
      } catch (err) {
        logWarn("Failed to connect to Redis for rate limiting, falling back to in-memory", err);
        return memoryStore;
      }
    })();
  }
  return redisStorePromise;
}

/**
 * Checks rate limits for a given identifier (e.g. client IP + action scope).
 * Uses Redis distributed sliding-window when REDIS_URL is configured, falling back to in-memory store.
 */
export async function checkRateLimit(
  key: string,
  options: RateLimitOptions,
  customStore?: RateLimitStore,
): Promise<RateLimitResult> {
  const store = customStore ?? (await getStore());
  try {
    return await store.consume(key, options);
  } catch (error) {
    logWarn("Rate limit store error, falling back to memory store", error);
    try {
      return await memoryStore.consume(key, options);
    } catch {
      return {
        allowed: true,
        limit: options.maxRequests,
        remaining: 1,
        retryAfter: options.windowSeconds,
      };
    }
  }
}

/** Resolves trusted client address according to PRD ADM-03 and docs/security/client-addresses.md. */
export function resolveClientIp(headers: Headers, trustedHops = 0): string {
  const forwardedFor = headers.get("x-forwarded-for");
  return clientAddress(forwardedFor, trustedHops) ?? "127.0.0.1";
}
