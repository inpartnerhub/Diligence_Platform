import { NextResponse, type NextRequest } from "next/server";

import { serverEnv } from "@/lib/config/env";
import {
  DEMO_GATE_COOKIE,
  DEMO_GATE_MAX_AGE,
  demoGatePassword,
  demoGateToken,
  sameString,
} from "@/lib/demo/gate";

export const dynamic = "force-dynamic";

/**
 * Public demo only (branch `demo`, DEMO_PUBLIC=true): checks the shared demo password and opens the gate for this
 * browser. Rate-limited with the other /auth routes by the proxy.
 */
export async function POST(request: NextRequest) {
  const password = demoGatePassword();
  if (!serverEnv.demoPublic || !password) {
    return new NextResponse(null, { status: 404 });
  }
  const form = await request.formData().catch(() => undefined);
  const given = form?.get("password");
  if (typeof given !== "string" || !sameString(given, password)) {
    return NextResponse.redirect(new URL("/demo-gate?error=1", serverEnv.appBaseUrl), 303);
  }
  const response = NextResponse.redirect(new URL("/demo", serverEnv.appBaseUrl), 303);
  response.cookies.set(DEMO_GATE_COOKIE, await demoGateToken(password, serverEnv.sessionSecret), {
    httpOnly: true,
    secure: serverEnv.secureCookies,
    sameSite: "lax",
    path: "/",
    maxAge: DEMO_GATE_MAX_AGE,
  });
  return response;
}
