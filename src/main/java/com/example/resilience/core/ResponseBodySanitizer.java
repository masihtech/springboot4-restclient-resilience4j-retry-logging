package com.example.resilience.core;

/**
 * Masks well-known secret fields in HTTP response bodies before they reach the logs, and caps
 * body length so a large payload cannot flood the log pipeline.
 */
public final class ResponseBodySanitizer {

    private static final int MAX_LENGTH = 2_000;

    private ResponseBodySanitizer() {
    }

    public static String sanitize(String body) {
        if (body == null) {
            return null;
        }

        String sanitized = body
                .replaceAll("(?i)\"access_token\"\\s*:\\s*\"[^\"]+\"", "\"access_token\":\"***\"")
                .replaceAll("(?i)\"refresh_token\"\\s*:\\s*\"[^\"]+\"", "\"refresh_token\":\"***\"")
                .replaceAll("(?i)\"password\"\\s*:\\s*\"[^\"]+\"", "\"password\":\"***\"")
                .replaceAll("(?i)\"secret\"\\s*:\\s*\"[^\"]+\"", "\"secret\":\"***\"")
                .replaceAll("(?i)\"apiKey\"\\s*:\\s*\"[^\"]+\"", "\"apiKey\":\"***\"");

        return sanitized.length() <= MAX_LENGTH
                ? sanitized
                : sanitized.substring(0, MAX_LENGTH) + "...[truncated]";
    }
}
