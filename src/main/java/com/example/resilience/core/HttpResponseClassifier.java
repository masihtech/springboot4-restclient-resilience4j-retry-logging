package com.example.resilience.core;

import org.springframework.http.HttpStatusCode;

/**
 * Decides whether an HTTP status code should trigger a retry. Retryable: all 5xx, plus
 * 408 Request Timeout and 429 Too Many Requests. Everything else that is an error
 * (notably 4xx like 400/401/403/404/422) is treated as non-retryable.
 */
public final class HttpResponseClassifier {

    private HttpResponseClassifier() {
    }

    public static boolean isRetryable(HttpStatusCode statusCode) {
        int value = statusCode.value();
        return statusCode.is5xxServerError()
                || value == 408
                || value == 429;
    }
}
