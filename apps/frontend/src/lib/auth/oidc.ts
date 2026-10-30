import "server-only";

import * as client from "openid-client";

import { serverEnv } from "@/lib/config/env";

let configuration: client.Configuration | undefined;

/**
 * OIDC client for the public `diligence-web` Keycloak client (Authorization Code + PKCE).
 *
 * Endpoints are built explicitly instead of discovered: browser-facing endpoints use the public issuer, while
 * back-channel calls (token, JWKS) use the internal address. Inside a container network the two differ, and the
 * token `iss` claim must still match the public issuer.
 */
export function oidcConfiguration(): client.Configuration {
  if (configuration) {
    return configuration;
  }
  const issuer = serverEnv.oidcIssuer;
  const internal = serverEnv.oidcInternalIssuer;
  const config = new client.Configuration(
    {
      issuer,
      authorization_endpoint: `${issuer}/protocol/openid-connect/auth`,
      end_session_endpoint: `${issuer}/protocol/openid-connect/logout`,
      token_endpoint: `${internal}/protocol/openid-connect/token`,
      jwks_uri: `${internal}/protocol/openid-connect/certs`,
      userinfo_endpoint: `${internal}/protocol/openid-connect/userinfo`,
    },
    serverEnv.oidcClientId,
    undefined,
    client.None(),
  );
  if (serverEnv.oidcAllowHttp) {
    client.allowInsecureRequests(config); // local stack only
  }
  configuration = config;
  return config;
}

export function callbackUrl(): string {
  return `${serverEnv.appBaseUrl}/auth/callback`;
}
