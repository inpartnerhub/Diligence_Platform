import "server-only";

import * as client from "openid-client";

import { callbackUrl, oidcConfiguration } from "@/lib/auth/oidc";
import { serverEnv } from "@/lib/config/env";

import { keycloakTotp } from "./totp";

type Tokens = Awaited<ReturnType<typeof client.authorizationCodeGrant>>;

/**
 * Demo sign-in (branch `demo`): runs the real Keycloak browser flow on the server for a prepared demo user — the
 * username/password form, then the OTP form with a code computed from the demo TOTP secret — and exchanges the
 * authorization code with PKCE like `/auth/callback`. The resulting tokens are ordinary ones (`amr` includes `otp`),
 * so step-up and every other check behave as after a manual sign-in. The browser never talks to Keycloak.
 */
export async function demoSignIn(username: string): Promise<Tokens> {
  const codeVerifier = client.randomPKCECodeVerifier();
  const state = client.randomState();
  const authorizationUrl = client.buildAuthorizationUrl(oidcConfiguration(), {
    redirect_uri: callbackUrl(),
    scope: "openid profile email",
    code_challenge: await client.calculatePKCECodeChallenge(codeVerifier),
    code_challenge_method: "S256",
    state,
    prompt: "login",
  });

  const browser = new CookieJarClient();
  let page = await browser.get(authorizationUrl.href);
  page = await browser.submit(page, "kc-form-login", {
    username,
    password: serverEnv.demoPassword,
    credentialId: "",
  });
  if (page.location === undefined && hasForm(page.body, "kc-otp-login-form")) {
    let next = await browser.submit(page, "kc-otp-login-form", {
      otp: keycloakTotp(serverEnv.demoTotpSecret),
    });
    if (next.location === undefined && hasForm(next.body, "kc-otp-login-form")) {
      // Keycloak refuses a code that was already used in its 30-second window (same role twice in a row).
      await sleep(msUntilNextPeriod());
      next = await browser.submit(next, "kc-otp-login-form", { otp: keycloakTotp(serverEnv.demoTotpSecret) });
    }
    page = next;
  }
  if (page.location === undefined || !page.location.startsWith(callbackUrl())) {
    throw new Error(`Demo sign-in for ${username} did not reach the callback (status ${page.status})`);
  }

  return client.authorizationCodeGrant(oidcConfiguration(), new URL(page.location), {
    pkceCodeVerifier: codeVerifier,
    expectedState: state,
  });
}

interface Page {
  url: string;
  status: number;
  body: string;
  location?: string;
}

/** Minimal browser for Keycloak's login pages: keeps cookies, does not follow redirects to the application. */
class CookieJarClient {
  private readonly cookies = new Map<string, string>();

  async get(url: string): Promise<Page> {
    return this.request(url, { method: "GET" });
  }

  async submit(page: Page, formId: string, fields: Record<string, string>): Promise<Page> {
    const action = formAction(page.body, formId);
    if (!action) {
      throw new Error(`Keycloak page has no form "${formId}" (status ${page.status})`);
    }
    return this.request(new URL(action, page.url).href, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams(fields).toString(),
    });
  }

  private async request(url: string, init: RequestInit): Promise<Page> {
    let current = internal(url);
    for (let hop = 0; hop < 5; hop++) {
      const response = await fetch(current, {
        ...init,
        headers: { ...(init.headers as Record<string, string>), Cookie: this.cookieHeader() },
        redirect: "manual",
        cache: "no-store",
      });
      for (const cookie of response.headers.getSetCookie()) {
        const [pair] = cookie.split(";");
        const index = pair?.indexOf("=") ?? -1;
        if (pair && index > 0) {
          this.cookies.set(pair.slice(0, index).trim(), pair.slice(index + 1).trim());
        }
      }
      const location = response.headers.get("location");
      if (response.status >= 300 && response.status < 400 && location) {
        const target = new URL(location, current).href;
        if (target.startsWith(serverEnv.appBaseUrl)) {
          return { url: current, status: response.status, body: "", location: target };
        }
        current = internal(target); // a redirect inside Keycloak
        init = { method: "GET" };
        continue;
      }
      return { url: current, status: response.status, body: await response.text() };
    }
    throw new Error("Too many redirects during demo sign-in");
  }

  private cookieHeader(): string {
    return [...this.cookies].map(([name, value]) => `${name}=${value}`).join("; ");
  }
}

/** Keycloak's public address may not be reachable from this server (containers): use the back-channel address. */
function internal(url: string): string {
  const publicIssuer = serverEnv.oidcIssuer;
  const internalIssuer = serverEnv.oidcInternalIssuer;
  const publicOrigin = new URL(publicIssuer).origin;
  const internalOrigin = new URL(internalIssuer).origin;
  return url.startsWith(publicOrigin) ? internalOrigin + url.slice(publicOrigin.length) : url;
}

function formAction(html: string, formId: string): string | undefined {
  const tag = new RegExp(`<form[^>]*\\bid="${formId}"[^>]*>`, "i").exec(html)?.[0];
  const action = tag && /\baction="([^"]+)"/i.exec(tag)?.[1];
  return action?.replaceAll("&amp;", "&");
}

function hasForm(html: string, formId: string): boolean {
  return formAction(html, formId) !== undefined;
}

function msUntilNextPeriod(): number {
  return 30_000 - (Date.now() % 30_000) + 500;
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
