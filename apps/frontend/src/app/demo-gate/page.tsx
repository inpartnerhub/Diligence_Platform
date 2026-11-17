import type { Metadata } from "next";
import { notFound } from "next/navigation";

import { serverEnv } from "@/lib/config/env";

import { DemoGateForm } from "./DemoGateForm";

export const dynamic = "force-dynamic";

export const metadata: Metadata = { title: "Demo — masuk" };

/** Public demo only (branch `demo`, DEMO_PUBLIC=true): the shared demo password before the role picker. */
export default async function DemoGatePage({ searchParams }: { searchParams: Promise<{ error?: string }> }) {
  if (!serverEnv.demoPublic) {
    notFound();
  }
  const { error } = await searchParams;
  return <DemoGateForm error={error === "1"} />;
}
