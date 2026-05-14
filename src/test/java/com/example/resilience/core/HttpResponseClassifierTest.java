package com.example.resilience.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatusCode;

import static org.assertj.core.api.Assertions.assertThat;

class HttpResponseClassifierTest {

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504, 408, 429})
    void retryableStatuses(int status) {
        assertThat(HttpResponseClassifier.isRetryable(HttpStatusCode.valueOf(status))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 422})
    void nonRetryableClientErrors(int status) {
        assertThat(HttpResponseClassifier.isRetryable(HttpStatusCode.valueOf(status))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 204})
    void successStatusesAreNotRetryable(int status) {
        assertThat(HttpResponseClassifier.isRetryable(HttpStatusCode.valueOf(status))).isFalse();
    }

    @Test
    void redirectsAreNotRetryable() {
        assertThat(HttpResponseClassifier.isRetryable(HttpStatusCode.valueOf(301))).isFalse();
        assertThat(HttpResponseClassifier.isRetryable(HttpStatusCode.valueOf(302))).isFalse();
    }
}
