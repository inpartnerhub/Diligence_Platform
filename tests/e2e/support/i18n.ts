import { readFileSync } from "node:fs";

// The UI follows each user's profile language, so the specs match labels in either language, straight from the app's
// message catalogues (keys are identical in both).
const catalogue = (lang: string) =>
  JSON.parse(
    readFileSync(new URL(`../../../apps/frontend/messages/${lang}.json`, import.meta.url), "utf8"),
  ) as Record<string, unknown>;
const catalogues = [catalogue("id"), catalogue("en")];

const lookup = (messages: Record<string, unknown>, key: string): string => {
  const value = key
    .split(".")
    .reduce<unknown>((node, part) => (node as Record<string, unknown>)?.[part], messages);
  if (typeof value !== "string") throw new Error(`No message ${key}`);
  return value;
};
const escape = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

/**
 * A pattern matching the label in Indonesian or English. ICU placeholders (`{name}`) match anything. `exact` makes the
 * label the end of the text, after an optional icon name ("check Approve"), so "Approve" does not match "Approve and
 * send"; otherwise it may appear anywhere in a longer text.
 */
export function label(key: string, { exact = false } = {}): RegExp {
  const alternatives = catalogues.map((m) => escape(lookup(m, key)).replace(/\\\{[^}]*\\\}/g, ".*"));
  const body = `(${alternatives.join("|")})`;
  return new RegExp(exact ? `(^|\\s)${body}\\s*$` : body);
}
