/** Liveness/readiness probe for Kubernetes. Intentionally dependency-free. */
export const dynamic = "force-dynamic";

export function GET() {
  return Response.json({ status: "UP" });
}
