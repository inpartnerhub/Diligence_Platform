/**
 * Server-side log lines, one JSON object each (#88, CodeQL `js/log-injection`). Error messages can carry values
 * from the request (IDs, form input); written as JSON, their line breaks are escaped, so they cannot forge or split
 * log entries. Field names follow ECS, like the backend's logs, so both are shipped and searched the same way.
 */
type Level = "error" | "warn";

function describe(error: unknown) {
  if (error instanceof Error) {
    return { type: error.name, message: error.message, stack_trace: error.stack };
  }
  return { message: String(error) };
}

function write(level: Level, message: string, error?: unknown) {
  const entry: Record<string, unknown> = {
    "@timestamp": new Date().toISOString(),
    "log.level": level,
    message,
  };
  if (error !== undefined) entry.error = describe(error);
  const line = JSON.stringify(entry);
  if (level === "error") console.error(line);
  else console.warn(line);
}

export const logError = (message: string, error?: unknown) => write("error", message, error);
export const logWarn = (message: string, error?: unknown) => write("warn", message, error);
