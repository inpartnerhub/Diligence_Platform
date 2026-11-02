import { describe, expect, it } from "vitest";

import {
  sanitizeOptionalText,
  sanitizeOptionalTextOrNull,
  sanitizeText,
  validateRequiredText,
} from "./inputSanitizer";

describe("inputSanitizer", () => {
  describe("sanitizeText", () => {
    it("trims leading and trailing whitespace", () => {
      expect(sanitizeText("   hello world   ")).toBe("hello world");
      expect(sanitizeText("\t\ntest\r\n")).toBe("test");
    });

    it("strips ASCII control characters in multiline mode while preserving newlines and tabs", () => {
      const input = "\x00\x01Hello\x07\x08 \x0EWorld\x7F\nLine 2\tTabbed";
      expect(sanitizeText(input, false)).toBe("Hello World\nLine 2\tTabbed");
    });

    it("strips all ASCII control characters and converts newlines to spaces in singleLine mode", () => {
      const input = "Project\nName\r\n\x00Code\t123\x7F";
      expect(sanitizeText(input, true)).toBe("Project Name Code 123");
    });

    it("handles null, undefined, or empty strings gracefully", () => {
      expect(sanitizeText(null)).toBe("");
      expect(sanitizeText(undefined)).toBe("");
      expect(sanitizeText("")).toBe("");
      expect(sanitizeText("   ")).toBe("");
    });
  });

  describe("validateRequiredText", () => {
    it("returns trimmed text for valid inputs", () => {
      expect(validateRequiredText("  Project Alpha  ", "Project name", true)).toBe("Project Alpha");
      expect(validateRequiredText("Finding description with\nnewlines", "Description")).toBe(
        "Finding description with\nnewlines",
      );
    });

    it("throws error for empty or whitespace-only inputs", () => {
      expect(() => validateRequiredText("   ", "Project name")).toThrow(
        "Project name cannot be empty or whitespace only",
      );
      expect(() => validateRequiredText("\x00\x01\t\n  ", "Title", true)).toThrow(
        "Title cannot be empty or whitespace only",
      );
    });

    it("throws error for null or undefined", () => {
      expect(() => validateRequiredText(null, "Title")).toThrow("Title is required");
      expect(() => validateRequiredText(undefined, "Title")).toThrow("Title is required");
    });
  });

  describe("sanitizeOptionalText", () => {
    it("returns sanitized string when non-empty", () => {
      expect(sanitizeOptionalText("  Category A  ", true)).toBe("Category A");
    });

    it("returns undefined when null, undefined, or empty/whitespace-only", () => {
      expect(sanitizeOptionalText(null)).toBeUndefined();
      expect(sanitizeOptionalText(undefined)).toBeUndefined();
      expect(sanitizeOptionalText("   ")).toBeUndefined();
      expect(sanitizeOptionalText("\x00\x07")).toBeUndefined();
    });
  });

  describe("sanitizeOptionalTextOrNull", () => {
    it("returns sanitized string when present and valid", () => {
      expect(sanitizeOptionalTextOrNull("  Recommendation  ")).toBe("Recommendation");
    });

    it("returns undefined when input is undefined", () => {
      expect(sanitizeOptionalTextOrNull(undefined)).toBeUndefined();
    });

    it("returns null when input is null or whitespace-only", () => {
      expect(sanitizeOptionalTextOrNull(null)).toBeNull();
      expect(sanitizeOptionalTextOrNull("   ")).toBeNull();
      expect(sanitizeOptionalTextOrNull("\x00\x05")).toBeNull();
    });
  });
});
