import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

import { adminApi } from "@/features/admin/api";
import { ProfileView } from "@/features/admin/ProfileView";
import { serverEnv } from "@/lib/config/env";

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("profile"))("title") };
}

export default async function ProfilePage() {
  const profile = await adminApi.profile();
  return <ProfileView profile={profile} accountUrl={`${serverEnv.oidcIssuer}/account`} />;
}
