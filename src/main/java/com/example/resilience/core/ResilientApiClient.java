package com.example.resilience.core;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-dependency facade that wires together one {@link RestClient} and the shared
 * {@link ResilientRestClientExecutor}. Domain clients obtain instances through
 * {@link ResilientApiClientFactory#forDependency(String)} and never deal with retry names
 * directly.
 *
 * <p>The typed {@code get} overloads deserialize the response body <em>after</em> the resilient
 * call returns. That placement is deliberate: retry and per-attempt logging operate on the raw
 * JSON, and a deserialization failure on a successful response is a client-side mapping bug,
 * not something to retry.
 */
@RequiredArgsConstructor
public class ResilientApiClient {

    private final String dependencyName;
    private final RestClient restClient;
    private final ResilientRestClientExecutor executor;
    private final ObjectMapper objectMapper;

    /** Retry-decorated GET returning the raw response body. */
    public String get(String uri) {
        return getEntity(uri).getBody();
    }

    /** Retry-decorated GET, deserialized into {@code type} (e.g. a record DTO). */
    public <T> T get(String uri, Class<T> type) {
        return deserialize(get(uri), type);
    }

    /** Retry-decorated GET, deserialized into a generic {@code type} (e.g. {@code List<FooDto>}). */
    public <T> T get(String uri, TypeReference<T> type) {
        return deserialize(get(uri), type);
    }

    /** Retry-decorated GET returning the full response entity. */
    public ResponseEntity<String> getEntity(String uri) {
        return executor.executeGet(restClient, dependencyName, dependencyName, uri);
    }

    /**
     * Retry-decorated idempotent mutation. The {@code idempotencyKey} is mandatory and forwarded
     * to the server as the {@code Idempotency-Key} header. There is intentionally no retry-less
     * plain-mutation method: non-idempotent calls must use the raw {@link RestClient} so that
     * unsafe retries are structurally impossible here.
     */
    public ResponseEntity<String> exchangeIdempotent(
            HttpMethod method,
            String uri,
            Object body,
            String idempotencyKey
    ) {
        return executor.executeIdempotentMutation(
                restClient, dependencyName, dependencyName, method, uri, body, idempotencyKey);
    }

    private <T> T deserialize(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (JacksonException ex) {
            throw new IllegalStateException(
                    "Failed to deserialize response from " + dependencyName + " into " + type.getSimpleName(), ex);
        }
    }

    private <T> T deserialize(String body, TypeReference<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (JacksonException ex) {
            throw new IllegalStateException(
                    "Failed to deserialize response from " + dependencyName + " into " + type.getType(), ex);
        }
    }
}
