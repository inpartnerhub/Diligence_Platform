import { describe, expect, it } from "vitest";

import {
  findingCategoriesToText,
  parseFindingCategories,
  parseFolders,
  parseRequestItems,
  requestItemsToText,
} from "./templateText";

describe("template text format", () => {
  it("round-trips request items, including pipes inside descriptions", () => {
    const items = [
      { workstream: "LEGAL" as const, category: "Corporate", description: "Deed | amendments" },
      { workstream: "TAX" as const, category: "Compliance", description: "SPT 3 years" },
    ];
    const parsed = parseRequestItems(requestItemsToText(items));
    expect(parsed).toEqual({ ok: true, value: items });
  });

  it("accepts lower-case workstreams and ignores blank lines", () => {
    expect(parseRequestItems("\n legal | Contracts | Top 20 contracts \n\n")).toEqual({
      ok: true,
      value: [{ workstream: "LEGAL", category: "Contracts", description: "Top 20 contracts" }],
    });
  });

  it("reports the first invalid line", () => {
    expect(parseRequestItems("LEGAL | A | ok\nUNKNOWN | B | x")).toEqual({ ok: false, line: 2 });
    expect(parseRequestItems("LEGAL | missing description")).toEqual({ ok: false, line: 1 });
    expect(parseFindingCategories("HR")).toEqual({ ok: false, line: 1 });
  });

  it("parses finding categories and folders", () => {
    const categories = [{ workstream: "FINANCIAL" as const, name: "Quality of earnings" }];
    expect(parseFindingCategories(findingCategoriesToText(categories))).toEqual({
      ok: true,
      value: categories,
    });
    expect(parseFolders(" 01 Corporate \n\n02 Finance")).toEqual(["01 Corporate", "02 Finance"]);
    expect(parseFolders(undefined)).toEqual([]);
  });
});
