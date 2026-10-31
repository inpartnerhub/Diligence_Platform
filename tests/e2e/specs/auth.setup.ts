import { test as setup } from "@playwright/test";

import { credentials, ROLES, signIn, statePath } from "../support/auth";

// One sign-in per role, saved as storage state for the flow specs. Sequential: Keycloak locks a user that signs in
// twice within a second, and rejects an OTP code that was already used.
setup.describe.configure({ mode: "serial" });

for (const role of ROLES) {
  setup(`sign in as ${role}`, async ({ page }) => {
    setup.setTimeout(75_000); // may wait for the next OTP window
    setup.skip(!credentials(role), `no credentials for ${role}`);
    await signIn(page, role);
    await page.context().storageState({ path: statePath(role) });
  });
}
