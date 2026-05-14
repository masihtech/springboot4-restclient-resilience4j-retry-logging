package com.example.resilience.core;

import com.example.resilience.client.NonRetryableExternalApiException;
import com.example.resilience.client.RetryableExternalApiException;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Core retry-aware executor for outbound HTTP calls. Owns attempt counting, per-attempt DEBUG
 * logging, response body sanitization, status classification, and integration with a
 * Resilience4j {@link Retry} instance. The {@code Retry.decorateSupplier} wrapper runs the
 * call synchronously on the caller thread, so the MDC correlation id survives every attempt.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResilientRestClientExecutor {

    private final RetryRegistry retryRegistry;

    /** Executes a retry-decorated GET. Safe to retry: GET is idempotent. */
    public ResponseEntity<String> executeGet(
            RestClient restClient,
            String retryName,
            String dependencyName,
            String uri
    ) {
        return execute(retryName, dependencyName, HttpMethod.GET, uri,
                () -> restClient.get().uri(uri));
    }

    /**
     * Executes a retry-decorated mutation that is safe to retry only because the caller supplies
     * an idempotency key, forwarded to the server as the {@code Idempotency-Key} header so the
     * server can deduplicate replays.
     */
    public ResponseEntity<String> executeIdempotentMutation(
            RestClient restClient,
            String retryName,
            String dependencyName,
            HttpMethod method,
            String uri,
            Object body,
            String idempotencyKey
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException(
                    "An idempotency key is required to retry a " + method + " on " + dependencyName);
        }
        return execute(retryName, dependencyName, method, uri, () -> {
            RestClient.RequestBodySpec spec = restClient.method(method).uri(uri)
                    .header("Idempotency-Key", idempotencyKey);
            if (body != null) {
                spec.body(body);
            }
            return spec;
        });
    }

    private ResponseEntity<String> execute(
            String retryName,
            String dependencyName,
            HttpMethod method,
            String uri,
            Supplier<RestClient.RequestHeadersSpec<?>> requestSpec
    ) {
        Retry retry = retryRegistry.retry(retryName);
        AtomicInteger attemptCounter = new AtomicInteger(0);

        Supplier<ResponseEntity<String>> supplier = Retry.decorateSupplier(retry, () -> {
            int attempt = attemptCounter.incrementAndGet();
            long startNanos = System.nanoTime();
            String correlationId = CorrelationIdContext.get();

            if (log.isDebugEnabled()) {
                log.debug(
                        "external_call_attempt_started dependency={} correlationId={} retryName={} attempt={} method={} uri={}",
                        dependencyName, correlationId, retryName, attempt, method, uri
                );
            }

            try {
                return requestSpec.get().exchange((request, clientResponse) -> {
                    HttpStatusCode statusCode = clientResponse.getStatusCode();
                    String responseBody = readBody(clientResponse, dependencyName, correlationId,
                            retryName, attempt, method, uri, startNanos);
                    long durationMs = (System.nanoTime() - startNanos) / 1_000_000;

                    if (log.isDebugEnabled()) {
                        log.debug(
                                "external_call_attempt_response dependency={} correlationId={} retryName={} attempt={} method={} uri={} status={} durationMs={} body={}",
                                dependencyName, correlationId, retryName, attempt,
                                request.getMethod(), request.getURI(),
                                statusCode.value(), durationMs,
                                ResponseBodySanitizer.sanitize(responseBody)
                        );
                    }

                    if (HttpResponseClassifier.isRetryable(statusCode)) {
                        throw new RetryableExternalApiException(
                                "Retryable HTTP response from " + dependencyName + ": " + statusCode.value(),
                                statusCode, responseBody
                        );
                    }

                    if (statusCode.isError()) {
                        throw new NonRetryableExternalApiException(
                                "Non-retryable HTTP response from " + dependencyName + ": " + statusCode.value(),
                                statusCode, responseBody
                        );
                    }

                    return ResponseEntity
                            .status(statusCode)
                            .headers(clientResponse.getHeaders())
                            .body(responseBody);
                });
            } catch (ResourceAccessException ex) {
                // Spring wraps transport-level I/O failures (connect/read timeouts, resets) here.
                logTransportFailure(dependencyName, correlationId, retryName, attempt, method, uri, startNanos, ex);
                throw new RetryableExternalApiException("Transport failure calling " + dependencyName, ex);
            } catch (RetryableExternalApiException | NonRetryableExternalApiException ex) {
                throw ex;
            } catch (Exception ex) {
                long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                if (log.isDebugEnabled()) {
                    log.debug(
                            "external_call_attempt_unexpected_failure dependency={} correlationId={} retryName={} attempt={} method={} uri={} durationMs={} errorClass={} errorMessage={}",
                            dependencyName, correlationId, retryName, attempt, method, uri, durationMs,
                            ex.getClass().getSimpleName(), ex.getMessage(), ex
                    );
                }
                throw ex;
            }
        });

        return supplier.get();
    }

    /** Reads the response body, treating a mid-stream I/O failure as a retryable transport error. */
    private String readBody(
            ClientHttpResponse clientResponse,
            String dependencyName,
            String correlationId,
            String retryName,
            int attempt,
            HttpMethod method,
            String uri,
            long startNanos
    ) {
        try {
            return StreamUtils.copyToString(clientResponse.getBody(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            logTransportFailure(dependencyName, correlationId, retryName, attempt, method, uri, startNanos, ex);
            throw new RetryableExternalApiException("IO failure reading response from " + dependencyName, ex);
        }
    }

    private void logTransportFailure(
            String dependencyName,
            String correlationId,
            String retryName,
            int attempt,
            HttpMethod method,
            String uri,
            long startNanos,
            Exception ex
    ) {
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        if (log.isDebugEnabled()) {
            log.debug(
                    "external_call_attempt_transport_failure dependency={} correlationId={} retryName={} attempt={} method={} uri={} durationMs={} errorClass={} errorMessage={}",
                    dependencyName, correlationId, retryName, attempt, method, uri, durationMs,
                    ex.getClass().getSimpleName(), ex.getMessage(), ex
            );
        }
    }
}
