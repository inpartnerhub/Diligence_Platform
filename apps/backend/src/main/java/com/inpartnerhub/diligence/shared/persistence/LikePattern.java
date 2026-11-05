package com.inpartnerhub.diligence.shared.persistence;

import java.util.Locale;

/**
 * Case-insensitive "contains" patterns for SQL {@code LIKE}. User input is escaped, so {@code %} and {@code _} in a
 * search term match literally. Use with {@code cb.like(cb.lower(expr), LikePattern.contains(q), LikePattern.ESCAPE)}.
 */
public final class LikePattern {

    public static final char ESCAPE = '\\';

    private LikePattern() {
    }

    public static String contains(String text) {
        var escaped = text.strip().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
