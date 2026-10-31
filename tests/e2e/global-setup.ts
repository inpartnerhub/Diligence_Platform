import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

/**
 * Recreates project E2E-001 on the local stack before a run (tests/e2e/fixtures/seed.sh) and passes its id on. Against
 * a deployed environment, set E2E_PROJECT_ID to a prepared project and E2E_SEED=0.
 */
export default function globalSetup(): void {
  if (process.env.E2E_PROJECT_ID || process.env.E2E_SEED === "0") return;
  const seed = fileURLToPath(new URL("./fixtures/seed.sh", import.meta.url));
  try {
    process.env.E2E_PROJECT_ID = execFileSync(seed, { encoding: "utf8" }).trim().split("\n").pop();
  } catch (error) {
    console.warn(
      `E2E fixture not created (flow specs will be skipped): ${(error as Error).message.split("\n")[0]}`,
    );
  }
}
