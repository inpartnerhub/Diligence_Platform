export type Query = Record<string, string | number | undefined>;

/** Appends non-empty query parameters to an API path. */
export function withQuery(path: `/api/${string}`, query: Query): `/api/${string}` {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== "") {
      params.set(key, String(value));
    }
  }
  const qs = params.toString();
  return `${path}${qs ? `?${qs}` : ""}`;
}
