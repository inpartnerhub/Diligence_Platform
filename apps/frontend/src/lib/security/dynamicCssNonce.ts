/**
 * Stand-in for `@rc-component/util/es/Dom/dynamicCSS` in production builds (wired in next.config.ts, issue #82).
 *
 * Ant Design's own styles carry the CSP nonce (`ConfigProvider csp`), but two helpers in rc-component insert `<style>`
 * through `updateCSS` without one, so `style-src 'nonce-…'` blocks them:
 * - `getScrollBarSize` / `getTargetScrollBarSize` (tables): a short-lived scrollbar measurement rule;
 * - `useScrollLocker` (Modal, Drawer): `html body { overflow-y: hidden }` — without it the page behind a modal scrolls.
 *
 * This module forwards every call to the original and adds the document's nonce when the caller passed none. The nonce
 * is the one of the document's own CSP (the first element carrying it), which stays valid for client navigations.
 */
import {
  clearContainerCache,
  injectCSS as originalInjectCSS,
  removeCSS,
  updateCSS as originalUpdateCSS,
} from "@rc-component/util/es/Dom/dynamicCSS";

type Options = NonNullable<Parameters<typeof originalInjectCSS>[1]>;

/** The caller's options with `csp.nonce` filled in from `nonce` when it has none. */
export function withNonce<T extends Options>(option: T | undefined, nonce: string | undefined): T {
  const options = (option ?? {}) as T;
  if (!nonce || options.csp?.nonce) return options;
  return { ...options, csp: { ...options.csp, nonce } };
}

/** Nonce of the current document's CSP; browsers hide the attribute, the property keeps it. */
export function documentNonce(doc: Pick<Document, "querySelector"> | undefined = globalThis.document) {
  const element = doc?.querySelector<HTMLElement>("script[nonce], style[nonce]");
  return element?.nonce || element?.getAttribute("nonce") || undefined;
}

export function injectCSS(css: string, option?: Options) {
  return originalInjectCSS(css, withNonce(option, documentNonce()));
}

export function updateCSS(css: string, key: string, option?: Options) {
  return originalUpdateCSS(css, key, withNonce(option, documentNonce()));
}

export { clearContainerCache, removeCSS };
