package com.inpartnerhub.diligence.shared.web;

/**
 * Encodes one CSV cell for exports that people open in Excel or LibreOffice (issue #85). Every cell is quoted, and a
 * value that a spreadsheet could read as a formula gets a leading apostrophe (OWASP "CSV Injection"): one that starts
 * with a tab, carriage return or line feed, or whose first character after any whitespace is {@code = + - @ |} or the
 * full-width {@code ＝ ＋ － ＠}. Spreadsheets trim leading spaces before deciding, so " =1+1" is a formula too.
 */
public final class CsvCells {

    private CsvCells() {
    }

    public static String cell(String value) {
        if (value == null || value.isEmpty()) {
            return "\"\"";
        }
        var text = isFormula(value) ? "'" + value : value;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    static boolean isFormula(String value) {
        char first = value.charAt(0);
        if (first == '\t' || first == '\r' || first == '\n') {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c)) {
                return switch (c) {
                    case '=', '+', '-', '@', '|', '＝', '＋', '－', '＠' -> true;
                    default -> false;
                };
            }
        }
        return false;
    }
}
