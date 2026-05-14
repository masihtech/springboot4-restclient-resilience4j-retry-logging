package com.example.resilience.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseBodySanitizerTest {

    @Test
    void masksAllKnownSecretFields() {
        String body = "{\"access_token\":\"a\",\"refresh_token\":\"b\",\"password\":\"c\","
                + "\"secret\":\"d\",\"apiKey\":\"e\"}";

        String sanitized = ResponseBodySanitizer.sanitize(body);

        assertThat(sanitized).contains("\"access_token\":\"***\"");
        assertThat(sanitized).contains("\"refresh_token\":\"***\"");
        assertThat(sanitized).contains("\"password\":\"***\"");
        assertThat(sanitized).contains("\"secret\":\"***\"");
        assertThat(sanitized).contains("\"apiKey\":\"***\"");
        assertThat(sanitized).doesNotContain("\"a\"", "\"b\"", "\"c\"", "\"d\"", "\"e\"");
    }

    @Test
    void masksCaseInsensitively() {
        String sanitized = ResponseBodySanitizer.sanitize("{\"Access_Token\":\"leak\"}");
        assertThat(sanitized).isEqualTo("{\"access_token\":\"***\"}");
    }

    @Test
    void leavesNonSecretJsonUntouched() {
        String body = "{\"id\":\"123\",\"name\":\"John\"}";
        assertThat(ResponseBodySanitizer.sanitize(body)).isEqualTo(body);
    }

    @Test
    void returnsNullForNull() {
        assertThat(ResponseBodySanitizer.sanitize(null)).isNull();
    }

    @Test
    void truncatesBodiesLongerThanTheLimit() {
        String body = "x".repeat(2_500);
        String sanitized = ResponseBodySanitizer.sanitize(body);
        assertThat(sanitized).hasSize(2_000 + "...[truncated]".length());
        assertThat(sanitized).endsWith("...[truncated]");
    }

    @Test
    void doesNotTruncateAtExactlyTheLimit() {
        String body = "x".repeat(2_000);
        String sanitized = ResponseBodySanitizer.sanitize(body);
        assertThat(sanitized).isEqualTo(body);
        assertThat(sanitized).doesNotContain("truncated");
    }
}
