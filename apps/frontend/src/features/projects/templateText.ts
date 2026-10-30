import { WORKSTREAMS, type TemplateDefinition, type WorkstreamType } from "./types";

/**
 * Plain-text editing format for template lists: one entry per line, fields separated by "|".
 * Kept separate from the UI so it is unit-testable.
 */
export function requestItemsToText(items: TemplateDefinition["requestItems"]): string {
  return items.map((i) => `${i.workstream} | ${i.category} | ${i.description}`).join("\n");
}

export function findingCategoriesToText(categories: TemplateDefinition["findingCategories"]): string {
  return categories.map((c) => `${c.workstream} | ${c.name}`).join("\n");
}

export type ParseResult<T> = { ok: true; value: T[] } | { ok: false; line: number };

function lines(text: string | undefined): [number, string][] {
  return (text ?? "")
    .split("\n")
    .map((line, i): [number, string] => [i + 1, line.trim()])
    .filter(([, line]) => line.length > 0);
}

function workstreamOf(value: string | undefined): WorkstreamType | undefined {
  const upper = value?.trim().toUpperCase();
  return WORKSTREAMS.find((w) => w === upper);
}

export function parseRequestItems(
  text: string | undefined,
): ParseResult<TemplateDefinition["requestItems"][number]> {
  const value: TemplateDefinition["requestItems"] = [];
  for (const [number, line] of lines(text)) {
    const [ws, category, ...rest] = line.split("|");
    const workstream = workstreamOf(ws);
    const description = rest.join("|").trim();
    if (!workstream || !category?.trim() || !description) {
      return { ok: false, line: number };
    }
    value.push({ workstream, category: category.trim(), description });
  }
  return { ok: true, value };
}

export function parseFindingCategories(
  text: string | undefined,
): ParseResult<TemplateDefinition["findingCategories"][number]> {
  const value: TemplateDefinition["findingCategories"] = [];
  for (const [number, line] of lines(text)) {
    const [ws, ...rest] = line.split("|");
    const workstream = workstreamOf(ws);
    const name = rest.join("|").trim();
    if (!workstream || !name) {
      return { ok: false, line: number };
    }
    value.push({ workstream, name });
  }
  return { ok: true, value };
}

export function parseFolders(text: string | undefined): string[] {
  return lines(text).map(([, line]) => line);
}
