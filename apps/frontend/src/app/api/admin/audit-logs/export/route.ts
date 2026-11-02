import type { NextRequest } from "next/server";

import { proxyGet } from "@/lib/api/proxy";

export const dynamic = "force-dynamic";

/** Audit log export as CSV (PRD ADM-04); the backend logs the export action. */
export async function GET(request: NextRequest) {
  const search = request.nextUrl.search;
  return proxyGet("/api/v1/admin/audit-logs/export", search);
}

