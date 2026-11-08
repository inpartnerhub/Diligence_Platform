// Sign-in helpers for setup(). They use the real flows: the browser login through the BFF and Authorization Code +
// PKCE against the public "diligence-web" client for an API token, so no test-only client is needed.
//
// Keycloak treats two password logins of one user less than a second apart as brute force and locks the user for a
// minute (quickLoginCheckMilliSeconds). So the password is entered once; every further app session and the API
// token reuse that Keycloak SSO session, the way a second browser tab would.
import http from "k6/http";
import { fail } from "k6";
import encoding from "k6/encoding";

const SESSION_COOKIE = "diligence_sid";
const loginParams = { tags: { name: "login", kind: "login" } };

/** Posts the Keycloak login form if the response shows it; with an SSO session Keycloak skips the form. */
function passLoginForm(res, username, password, params = loginParams) {
  const action = res.html().find("#kc-form-login").attr("action");
  return action ? http.post(action, { username, password, credentialId: "" }, params) : res;
}

function sessionId(appUrl, res) {
  const sid = http.cookieJar().cookiesForURL(appUrl)[SESSION_COOKIE];
  if (!sid || !res.url.startsWith(appUrl)) fail(`sign-in failed: ${res.status} ${res.url}`);
  return sid[0] ?? sid;
}

/**
 * Opens `count` app sessions (/auth/login → Keycloak → /auth/callback) and returns their session cookies. Only the
 * first one enters the password.
 */
export function appSessions(appUrl, username, password, count) {
  const jar = http.cookieJar();
  const sessions = [];
  for (let i = 0; i < count; i++) {
    jar.delete(appUrl, SESSION_COOKIE);
    const res = passLoginForm(http.get(`${appUrl}/auth/login?returnTo=%2F`, loginParams), username, password);
    sessions.push(sessionId(appUrl, res));
  }
  return sessions;
}

/** Gives this VU one of the sessions opened in setup(), like a signed-in browser tab. */
export function useAppSession(appUrl, sessions) {
  http.cookieJar().set(appUrl, SESSION_COOKIE, sessions[(__VU - 1) % sessions.length], { path: "/" });
}

const base64url = (bytes) => encoding.b64encode(bytes, "rawurl");

/**
 * Tokens for direct API calls (Authorization Code + PKCE). The access token lives for the realm's token lifespan
 * (5 min); pass the result to {@link bearer}, which refreshes it for runs that last longer.
 */
export async function apiTokens({ issuer, clientId, redirectUri, username, password }) {
  const verifier = base64url(crypto.getRandomValues(new Uint8Array(32)).buffer);
  const ascii = new Uint8Array(verifier.length);
  for (let i = 0; i < verifier.length; i++) ascii[i] = verifier.charCodeAt(i);
  const challenge = base64url(await crypto.subtle.digest("SHA-256", ascii));
  const query = [
    `client_id=${clientId}`,
    "response_type=code",
    "scope=openid",
    `redirect_uri=${encodeURIComponent(redirectUri)}`,
    `code_challenge=${challenge}`,
    "code_challenge_method=S256",
    "state=k6",
  ].join("&");
  const params = { ...loginParams, redirects: 0 };
  const authorize = http.get(`${issuer}/protocol/openid-connect/auth?${query}`, params);
  const redirect = passLoginForm(authorize, username, password, params);
  const code = /[?&]code=([^&]+)/.exec(redirect.headers.Location || "");
  if (!code) fail(`no authorization code (status ${redirect.status})`);
  const token = http.post(
    `${issuer}/protocol/openid-connect/token`,
    {
      grant_type: "authorization_code",
      client_id: clientId,
      code: decodeURIComponent(code[1]),
      redirect_uri: redirectUri,
      code_verifier: verifier,
    },
    loginParams,
  );
  if (token.status !== 200) fail(`token exchange failed: ${token.status}`);
  return { issuer, clientId, ...tokenFields(token) };
}

function tokenFields(res) {
  return {
    access: res.json("access_token"),
    refresh: res.json("refresh_token"),
    expiresAt: Date.now() + res.json("expires_in") * 1000,
  };
}

// Per VU: the tokens this VU currently uses. setup() data is read-only, so each VU refreshes its own copy. The realm
// does not rotate refresh tokens, so every VU can refresh from the one obtained in setup().
let current = null;

/** Authorization header value for the API; refreshes the access token a minute before it expires. */
export function bearer(tokens) {
  if (!current) current = { ...tokens };
  if (Date.now() > current.expiresAt - 60000) {
    const res = http.post(
      `${current.issuer}/protocol/openid-connect/token`,
      { grant_type: "refresh_token", client_id: current.clientId, refresh_token: current.refresh },
      loginParams,
    );
    if (res.status !== 200) fail(`token refresh failed: ${res.status}`);
    current = { ...current, ...tokenFields(res) };
  }
  return `Bearer ${current.access}`;
}
