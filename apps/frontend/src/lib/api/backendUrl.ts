/**
 * Resolves a backend path. IDs reach these paths from route parameters and server-action arguments, which callers
 * control; encodeURIComponent leaves "." and ".." intact and URL resolution would then move the request to another
 * endpoint. Such paths are rejected instead.
 */
export function backendUrl(path: string, base: string): URL {
  const pathname = path.split(/[?#]/, 1)[0] ?? "";
  if (pathname.split("/").some((segment) => segment === "." || segment === "..")) {
    throw new Error(`Refusing backend path with dot segments: ${pathname}`);
  }
  return new URL(path, base);
}
