import { PortfolioDashboardView } from "@/features/projects/PortfolioDashboardView";
import { projectsApi } from "@/features/projects/api";
import { requireAccess } from "@/lib/auth/session";

/** Portfolio dashboard (PRD PRJ-05). Cross-project monitoring of progress, fulfillment, and top risks. */
export default async function DashboardPage() {
  await requireAccess("dashboard");
  const portfolio = await projectsApi.portfolio();
  return <PortfolioDashboardView portfolio={portfolio} />;
}
