import "server-only";

import { ApiError } from "@/lib/api/client";

import { STEP_UP_REQUIRED } from "./stepUp";

/** Marks a failed server-action result that needs a fresh sign-in before it can succeed (#65). */
export function stepUpFlag(error: unknown): { stepUp?: true } {
  return error instanceof ApiError && error.problem.type === STEP_UP_REQUIRED ? { stepUp: true } : {};
}
