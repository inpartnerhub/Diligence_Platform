import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

import { projectsApi } from "@/features/projects/api";
import { TemplatesView } from "@/features/projects/TemplatesView";

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("templates"))("title") };
}

export default async function TemplatesPage() {
  return <TemplatesView templates={await projectsApi.templates()} />;
}
