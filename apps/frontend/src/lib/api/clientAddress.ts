/**
 * The browser's address from an `X-Forwarded-For` chain (PRD ADM-03, DOC-04). Every proxy appends the address it
 * received the request from, so only the entries added by our own proxies can be trusted: with `trustedHops`
 * proxies in front of this server the client is the entry `trustedHops` places from the right. Anything further
 * left was sent by the browser and is ignored. Without a proxy (`trustedHops` 0) Next.js fills the header with the
 * socket address only when the request carries none, so the last entry is the best available answer.
 */
export function clientAddress(
  forwardedFor: string | null | undefined,
  trustedHops: number,
): string | undefined {
  const entries = (forwardedFor ?? "")
    .split(",")
    .map((entry) => entry.trim())
    .filter(Boolean);
  if (entries.length === 0) return undefined;
  const index = entries.length - Math.max(trustedHops, 1);
  return index >= 0 ? entries[index] : undefined;
}
