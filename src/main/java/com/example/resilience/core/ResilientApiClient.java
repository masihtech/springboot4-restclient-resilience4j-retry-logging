package com.example.resilience.core;

import com.example.resilience.client.ExternalApiUnavailableException;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.function.Supplier;

/**
 * Per-dependency facade that wires together one {@link RestClient}, the shared
 * {@link ResilientRestClientExecutor}, and (optionally) a circuit breaker. Domain clients
 * obtain instances through {@link ResilientApiClientFactory#forDependency(String)} and never
 * deal with retry names, circuit breaker factories, or fallbacks directly.
 *
 * <p>Resilience ordering is circuit breaker outside, retry inside: one business call enters the
 * circuit breaker; inside it the executor may make several HTTP attempts.
 *
 * <p>The typed {@code get} overloads deserialize the response body <em>after</em> the resilient
 * call returns. That placement is deliberate: retry, circuit breaker, and per-attempt logging
 * all operate on the raw JSON, and a deserialization failure on a successful response is a
 * client-side mapping bug — not something to retry.
 */
public class ResilientApiClient {

    private final String dependencyName;
    private final RestClient restClient;
    private final ResilientRestClientExecutor executor;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;
    private final String circuitBreakerName;
    private final ObjectMapper objectMapper;

    public ResilientApiClient(
            String dependencyName,
            RestClient restClient,
            ResilientRestClientExecutor executor,
            CircuitBreakerFactory<?, ?> circuitBreakerFactory,
            String circuitBreakerName,
            ObjectMapper objectMapper
    ) {
        this.dependencyName = dependencyName;
        this.restClient = restClient;
        this.executor = executor;
        this.circuitBreakerFactory = circuitBreakerFactory;
        this.circuitBreakerName = circuitBreakerName;
        this.objectMapper = objectMapper;
    }

    /** Retry-decorated, circuit-breaker-guarded GET returning the raw response body. */
    public String get(String uri) {
        return get(ApiRequest.uri(uri));
    }

    /** Retry-decorated, circuit-breaker-guarded GET returning the raw response body. */
    public String get(ApiRequest request) {
        return getEntity(request).getBody();
    }

    /** Retry-decorated, circuit-breaker-guarded GET, deserialized into {@code type} (e.g. a record DTO). */
    public <T> T get(String uri, Class<T> type) {
        return get(ApiRequest.uri(uri), type);
    }

    /** Retry-decorated, circuit-breaker-guarded GET, deserialized into {@code type} (e.g. a record DTO). */
    public <T> T get(ApiRequest request, Class<T> type) {
        return deserialize(get(request), type);
    }

    /** Retry-decorated, circuit-breaker-guarded GET, deserialized into a generic {@code type} (e.g. {@code List<FooDto>}). */
    public <T> T get(String uri, TypeReference<T> type) {
        return get(ApiRequest.uri(uri), type);
    }

    /** Retry-decorated, circuit-breaker-guarded GET, deserialized into a generic {@code type} (e.g. {@code List<FooDto>}). */
    public <T> T get(ApiRequest request, TypeReference<T> type) {
        return deserialize(get(request), type);
    }

    /** Retry-decorated, circuit-breaker-guarded GET returning the full response entity. */
    public ResponseEntity<String> getEntity(String uri) {
        return getEntity(ApiRequest.uri(uri));
    }

    /** Retry-decorated, circuit-breaker-guarded GET returning the full response entity. */
    public ResponseEntity<String> getEntity(ApiRequest request) {
        return run(() -> executor.executeGet(restClient, dependencyName, dependencyName, request));
    }

    /**
     * Retry-decorated, circuit-breaker-guarded idempotent mutation. The {@code idempotencyKey}
     * is mandatory and forwarded to the server as the {@code Idempotency-Key} header. There is
     * intentionally no retry-less plain-mutation method — non-idempotent calls must use the raw
     * {@link RestClient} so that unsafe retries are structurally impossible here.
     */
    public ResponseEntity<String> exchangeIdempotent(
            HttpMethod method,
            String uri,
            Object body,
            String idempotencyKey
    ) {
        return exchangeIdempotent(method, ApiRequest.uri(uri), body, idempotencyKey);
    }

    public ResponseEntity<String> exchangeIdempotent(
            HttpMethod method,
            ApiRequest request,
            Object body,
            String idempotencyKey
    ) {
        return run(() -> executor.executeIdempotentMutation(
                restClient, dependencyName, dependencyName, method, request, body, idempotencyKey));
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

    private ResponseEntity<String> run(Supplier<ResponseEntity<String>> call) {
        if (circuitBreakerName == null) {
            return call.get();
        }
        CircuitBreaker circuitBreaker = circuitBreakerFactory.create(circuitBreakerName);
        return circuitBreaker.run(call, this::fallback);
    }

    private ResponseEntity<String> fallback(Throwable throwable) {
        throw new ExternalApiUnavailableException(
                dependencyName + " unavailable. correlationId=" + CorrelationIdContext.get(),
                throwable
        );
    }
}
