/**
 * Password gate for a public demo (branch `demo`, DEMO_PUBLIC=true): visitors enter one shared password before the
 * role picker. The cookie holds a hash of the password and the session secret, never the password itself, so it
 * stops working when either changes. Works in the proxy and in route handlers (Web Crypto only).
 */
export const DEMO_GATE_COOKIE = "diligence_demo_gate";
export const DEMO_GATE_MAX_AGE = 12 * 60 * 60;
const MIN_PASSWORD_LENGTH = 8;

/** The gate password when a public demo is configured; undefined otherwise (the public demo then stays off). */
export function demoGatePassword(env: NodeJS.ProcessEnv = process.env): string | undefined {
  const password = env.DEMO_GATE_PASSWORD ?? "";
  return env.DEMO_PUBLIC === "true" && password.length >= MIN_PASSWORD_LENGTH ? password : undefined;
}

export async function demoGateToken(password: string, secret: string): Promise<string> {
  const data = new TextEncoder().encode(`diligence-demo-gate:${password}:${secret}`);
  const digest = await crypto.subtle.digest("SHA-256", data);
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, "0")).join("");
}

/** Constant-time comparison of two strings of possibly different length. */
export function sameString(a: string, b: string): boolean {
  const left = new TextEncoder().encode(a);
  const right = new TextEncoder().encode(b);
  let diff = left.length ^ right.length;
  for (let i = 0; i < Math.max(left.length, right.length); i++) {
    diff |= (left[i] ?? 0) ^ (right[i] ?? 0);
  }
  return diff === 0;
}

export async function isDemoGateOpen(cookieValue: string | undefined, env: NodeJS.ProcessEnv = process.env) {
  const password = demoGatePassword(env);
  const secret = env.SESSION_SECRET;
  if (!password || !secret || !cookieValue) return false;
  return sameString(cookieValue, await demoGateToken(password, secret));
}
