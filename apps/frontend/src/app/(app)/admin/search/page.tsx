import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

import { adminApi } from "@/features/admin/api";
import { SearchIndexView } from "@/features/admin/SearchIndexView";

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("searchIndex"))("title") };
}

export default async function SearchIndexPage() {
  return <SearchIndexView run={await adminApi.searchReindex()} />;
}
