/**
 * Input sanitization and validation utilities for defense-in-depth across frontend actions and form submissions.
 * Normalizes user-submitted strings by stripping unwanted ASCII control characters and trimming whitespace.
 */

// Strips non-printable ASCII controls (0x00-0x1F and 0x7F) while preserving newline (\n), carriage return (\r), and tab (\t)
const MULTILINE_CONTROL_CHARS = /[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/g;

// Strips all ASCII controls including newlines/carriage returns for single-line fields
const SINGLE_LINE_CONTROL_CHARS = /[\x00-\x1F\x7F]/g;

/**
 * Strips ASCII control characters and trims leading/trailing whitespace.
 */
export function sanitizeText(value: string | undefined | null, singleLine = false): string {
  if (value === undefined || value === null) return "";
  const pattern = singleLine ? SINGLE_LINE_CONTROL_CHARS : MULTILINE_CONTROL_CHARS;
  return value.replace(pattern, "").trim();
}

/**
 * Validates and sanitizes a required string field.
 * Throws an error if the sanitized result is empty or whitespace-only.
 */
export function validateRequiredText(
  value: string | undefined | null,
  fieldName: string,
  singleLine = false,
): string {
  if (value === undefined || value === null) {
    throw new Error(`${fieldName} is required`);
  }
  const sanitized = sanitizeText(value, singleLine);
  if (sanitized.length === 0) {
    throw new Error(`${fieldName} cannot be empty or whitespace only`);
  }
  return sanitized;
}

/**
 * Sanitizes an optional string field.
 * Returns the sanitized string, or undefined if the string is empty or undefined/null.
 */
export function sanitizeOptionalText(
  value: string | undefined | null,
  singleLine = false,
): string | undefined {
  if (value === undefined || value === null) return undefined;
  const sanitized = sanitizeText(value, singleLine);
  return sanitized.length > 0 ? sanitized : undefined;
}

/**
 * Sanitizes an optional string field that permits explicit null.
 * Returns the sanitized string, null if null or empty, or undefined if undefined.
 */
export function sanitizeOptionalTextOrNull(
  value: string | undefined | null,
  singleLine = false,
): string | null | undefined {
  if (value === undefined) return undefined;
  if (value === null) return null;
  const sanitized = sanitizeText(value, singleLine);
  return sanitized.length > 0 ? sanitized : null;
}
