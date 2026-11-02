import { describe, expect, it, beforeEach } from "vitest";

import {
  checkRateLimit,
  MemoryRateLimitStore,
  RedisRateLimitStore,
  resolveClientIp,
  type RedisClientLike,
} from "./rateLimiter";

describe("rateLimiter", () => {
  let store: MemoryRateLimitStore;

  beforeEach(() => {
    store = new MemoryRateLimitStore();
  });

  describe("MemoryRateLimitStore", () => {
    it("allows requests under the limit", async () => {
      const options = { windowSeconds: 60, maxRequests: 3 };

      const r1 = await checkRateLimit("test:ip1", options, store);
      expect(r1.allowed).toBe(true);
      expect(r1.remaining).toBe(2);

      const r2 = await checkRateLimit("test:ip1", options, store);
      expect(r2.allowed).toBe(true);
      expect(r2.remaining).toBe(1);

      const r3 = await checkRateLimit("test:ip1", options, store);
      expect(r3.allowed).toBe(true);
      expect(r3.remaining).toBe(0);
    });

    it("blocks requests over the limit and returns retryAfter", async () => {
      const options = { windowSeconds: 60, maxRequests: 2 };

      await checkRateLimit("test:ip2", options, store);
      await checkRateLimit("test:ip2", options, store);

      const blocked = await checkRateLimit("test:ip2", options, store);
      expect(blocked.allowed).toBe(false);
      expect(blocked.remaining).toBe(0);
      expect(blocked.retryAfter).toBeGreaterThan(0);
      expect(blocked.retryAfter).toBeLessThanOrEqual(60);
    });

    it("isolates different keys/IPs", async () => {
      const options = { windowSeconds: 60, maxRequests: 1 };

      const r1 = await checkRateLimit("test:user1", options, store);
      expect(r1.allowed).toBe(true);

      const r2 = await checkRateLimit("test:user1", options, store);
      expect(r2.allowed).toBe(false);

      // user2 should be unaffected
      const r3 = await checkRateLimit("test:user2", options, store);
      expect(r3.allowed).toBe(true);
    });
  });

  describe("RedisRateLimitStore", () => {
    it("evaluates sliding window atomically and allows under-quota requests", async () => {
      const mockClient: RedisClientLike = {
        eval: async () => [1, 29, 60], // allowed = 1, remaining = 29, retryAfter = 60
      };
      const redisStore = new RedisRateLimitStore(mockClient);
      const res = await redisStore.consume("auth:10.0.0.1", { windowSeconds: 60, maxRequests: 30 });

      expect(res.allowed).toBe(true);
      expect(res.limit).toBe(30);
      expect(res.remaining).toBe(29);
      expect(res.retryAfter).toBe(60);
    });

    it("evaluates sliding window and blocks over-quota requests", async () => {
      const mockClient: RedisClientLike = {
        eval: async () => [0, 0, 42], // allowed = 0, remaining = 0, retryAfter = 42
      };
      const redisStore = new RedisRateLimitStore(mockClient);
      const res = await redisStore.consume("auth:10.0.0.1", { windowSeconds: 60, maxRequests: 30 });

      expect(res.allowed).toBe(false);
      expect(res.limit).toBe(30);
      expect(res.remaining).toBe(0);
      expect(res.retryAfter).toBe(42);
    });
  });

  describe("checkRateLimit fallback", () => {
    it("falls back to memory store when custom store throws", async () => {
      const failingStore = {
        async consume() {
          throw new Error("Redis connection lost");
        },
      };

      const res = await checkRateLimit("test:fallback", { windowSeconds: 60, maxRequests: 5 }, failingStore);
      expect(res.allowed).toBe(true);
      expect(res.remaining).toBe(4);
    });
  });

  describe("resolveClientIp", () => {
    it("resolves client IP from trusted proxies according to client-addresses.md", () => {
      const headers = new Headers();
      headers.set("x-forwarded-for", "1.2.3.4, 203.0.113.195");

      // 1 hop behind Kong
      expect(resolveClientIp(headers, 1)).toBe("203.0.113.195");

      // 0 hops (local direct)
      expect(resolveClientIp(headers, 0)).toBe("203.0.113.195");

      // fallback when missing
      expect(resolveClientIp(new Headers(), 0)).toBe("127.0.0.1");
    });
  });
});
