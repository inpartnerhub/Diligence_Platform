import "server-only";

import { getIronSession } from "iron-session";
import { cookies } from "next/headers";

import { sessionCookieOptions } from "./session";

/** PKCE verifier, state and post-login target, kept in a sealed cookie for the 10 minutes a login may take. */
export interface LoginTransaction {
  codeVerifier?: string;
  state?: string;
  returnTo?: string;
}

export async function loginTransaction() {
  return getIronSession<LoginTransaction>(await cookies(), {
    ...sessionCookieOptions(),
    cookieName: "diligence_auth_tx",
    ttl: 600,
  });
}
