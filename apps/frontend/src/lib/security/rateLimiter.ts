import { clientAddress } from "../api/clientAddress";

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

const memoryStore = new MemoryRateLimitStore();

/**
 * Checks rate limits for a given identifier (e.g. client IP + action scope).
 * Uses in-memory sliding window store by default, falling back safely on errors.
 */
export async function checkRateLimit(
  key: string,
  options: RateLimitOptions,
  store: RateLimitStore = memoryStore,
): Promise<RateLimitResult> {
  try {
    return await store.consume(key, options);
  } catch (error) {
    console.warn("Rate limit store error, allowing request:", error);
    return {
      allowed: true,
      limit: options.maxRequests,
      remaining: 1,
      retryAfter: options.windowSeconds,
    };
  }
}

/** Resolves trusted client address according to PRD ADM-03 and docs/security/client-addresses.md. */
export function resolveClientIp(headers: Headers, trustedHops = 0): string {
  const forwardedFor = headers.get("x-forwarded-for");
  return clientAddress(forwardedFor, trustedHops) ?? "127.0.0.1";
}
