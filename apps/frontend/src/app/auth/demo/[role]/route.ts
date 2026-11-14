import { NextResponse, type NextRequest } from "next/server";

import { sessionCookie } from "@/lib/auth/session";
import { deleteSession } from "@/lib/auth/session-store";
import { startSession } from "@/lib/auth/start-session";
import { serverEnv } from "@/lib/config/env";
import { demoRole } from "@/lib/demo/roles";
import { demoSignIn } from "@/lib/demo/sign-in";
import { logError } from "@/lib/log";

export const dynamic = "force-dynamic";

/** Demo mode only (branch `demo`, local stack): signs in as the demo user of a role and opens the dashboard. */
export async function GET(_request: NextRequest, { params }: { params: Promise<{ role: string }> }) {
  if (!serverEnv.demoMode) {
    return new NextResponse(null, { status: 404 });
  }
  const role = demoRole((await params).role);
  if (!role) {
    return NextResponse.redirect(new URL("/demo", serverEnv.appBaseUrl));
  }

  // Switching roles: drop the current session first.
  const current = await sessionCookie();
  if (current.sid) {
    await deleteSession(current.sid);
    current.destroy();
  }

  try {
    const tokens = await demoSignIn(role.username);
    if (await startSession(tokens)) {
      return NextResponse.redirect(new URL("/", serverEnv.appBaseUrl)); // the home page picks the landing page per role
    }
  } catch (error) {
    logError(`Demo sign-in as ${role.username} failed`, error);
  }
  return NextResponse.redirect(new URL("/demo?error=1", serverEnv.appBaseUrl));
}
