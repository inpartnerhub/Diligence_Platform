# ADR-0005: Backend-for-frontend authentication with server-side sessions

- **Status:** Accepted
- **Date:** 2026-10-30
- **Deciders:** Tech Lead / Architect, Security Officer

## Context

The web app handles highly sensitive data for external users. Tokens stored in the browser (localStorage,
JS-readable cookies) are exposed to XSS; MFA and SSO must be handled by Keycloak (PRD ADM-02).

## Decision

The Next.js server is the OIDC client (`diligence-web`, Authorization Code + PKCE, `openid-client`). Tokens are kept
server-side in Redis keyed by a random session id; the browser only receives that id sealed in an `HttpOnly`,
`SameSite=Lax` cookie (`iron-session`). Server components and server actions call the API through Kong with the
user's access token, refreshing it when it is about to expire. Logout is a same-origin POST that deletes the
session and ends the Keycloak session.

## Alternatives considered

| Option | Pros | Cons |
|--------|------|------|
| SPA with tokens in the browser (PKCE in JS) | No session store | XSS can exfiltrate tokens; refresh tokens in the browser |
| Auth.js (next-auth v5) | Less code | Still beta for this Next.js line; token cookie size limits with Keycloak tokens |
| Encrypted cookie holding all tokens | Stateless | Keycloak tokens exceed the 4 KB cookie limit; no server-side revocation |

## Consequences

- Redis is required by the frontend (`REDIS_URL`); sessions survive frontend restarts and scale horizontally.
- Server-side revocation: deleting the Redis key logs a user out immediately.
- Browser-facing and back-channel issuer URLs are configured separately (`OIDC_ISSUER`, `OIDC_INTERNAL_ISSUER`).
