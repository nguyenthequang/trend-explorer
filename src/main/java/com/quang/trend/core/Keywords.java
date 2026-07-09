package com.quang.trend.core;

import java.util.regex.Pattern;

/** Keyword normalization for the search endpoint (build plan §7). */
public final class Keywords {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private Keywords() {}

    /**
     * Trim, lowercase, collapse internal whitespace.
     * Rejects inputs shorter than 2 or longer than 64 characters, and URLs.
     *
     * @throws IllegalArgumentException when the input is invalid
     */
    public static String normalize(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("keyword is required");
        }
        String k = WHITESPACE.matcher(raw.trim().toLowerCase()).replaceAll(" ");
        if (k.length() < 2 || k.length() > 64) {
            throw new IllegalArgumentException("keyword must be 2-64 characters");
        }
        if (k.contains("://") || k.startsWith("www.")) {
            throw new IllegalArgumentException("keyword must not be a URL");
        }
        return k;
    }
}
