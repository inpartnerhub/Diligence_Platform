/** Problem type the backend returns when an action needs a fresh sign-in with MFA (step-up, #62, #65). */
export const STEP_UP_REQUIRED = "urn:diligence:problem:step-up-required";

/** Signs in again (password + MFA, no SSO reuse) and returns to `returnTo`. */
export function stepUpLoginUrl(returnTo: string): string {
  return `/auth/login?stepUp=1&returnTo=${encodeURIComponent(returnTo)}`;
}
