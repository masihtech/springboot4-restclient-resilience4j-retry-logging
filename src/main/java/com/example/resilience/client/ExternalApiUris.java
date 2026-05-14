package com.example.resilience.client;

import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

final class ExternalApiUris {

    private ExternalApiUris() {
    }

    static String encodePathSegment(String value) {
        return UriUtils.encodePathSegment(value, StandardCharsets.UTF_8);
    }
}
