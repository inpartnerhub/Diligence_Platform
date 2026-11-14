import type { Metadata } from "next";
import { notFound } from "next/navigation";

import { serverEnv } from "@/lib/config/env";
import { DEMO_ROLES } from "@/lib/demo/roles";

import { DemoRolePicker } from "./DemoRolePicker";

export const dynamic = "force-dynamic";

export const metadata: Metadata = { title: "Demo — pilih peran" };

/** Demo mode only (branch `demo`, local stack): one card per role; a click signs in as that role's demo user. */
export default async function DemoPage({ searchParams }: { searchParams: Promise<{ error?: string }> }) {
  if (!serverEnv.demoMode) {
    notFound();
  }
  const { error } = await searchParams;
  return <DemoRolePicker roles={DEMO_ROLES} error={error === "1"} />;
}
