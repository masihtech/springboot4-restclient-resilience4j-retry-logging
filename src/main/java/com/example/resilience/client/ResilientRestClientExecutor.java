package com.example.resilience.client;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class ResilientRestClientExecutor {

    private final RetryRegistry retryRegistry;

    public ResponseEntity<String> executeGet(
            RestClient restClient,
            String retryName,
            String dependencyName,
            String correlationId,
            String uri
    ) {
        Retry retry = retryRegistry.retry(retryName);
        AtomicInteger attemptCounter = new AtomicInteger(0);

        Supplier<ResponseEntity<String>> supplier = Retry.decorateSupplier(retry, () -> {
            int attempt = attemptCounter.incrementAndGet();
            long startNanos = System.nanoTime();

            if (log.isDebugEnabled()) {
                log.debug(
                        "external_call_attempt_started dependency={} correlationId={} retryName={} attempt={} method=GET uri={}",
                        dependencyName,
                        correlationId,
                        retryName,
                        attempt,
                        uri
                );
            }

            try {
                return restClient
                        .get()
                        .uri(uri)
                        .exchange((request, clientResponse) -> {
                            HttpStatusCode statusCode = clientResponse.getStatusCode();
                            String responseBody = StreamUtils.copyToString(clientResponse.getBody(), StandardCharsets.UTF_8);
                            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;

                            if (log.isDebugEnabled()) {
                                log.debug(
                                        "external_call_attempt_response dependency={} correlationId={} retryName={} attempt={} method={} uri={} status={} durationMs={} body={}",
                                        dependencyName,
                                        correlationId,
                                        retryName,
                                        attempt,
                                        request.getMethod(),
                                        request.getURI(),
                                        statusCode.value(),
                                        durationMs,
                                        safeBody(responseBody)
                                );
                            }

                            if (isRetryableStatus(statusCode)) {
                                throw new RetryableExternalApiException(
                                        "Retryable HTTP response from " + dependencyName + ": " + statusCode.value(),
                                        statusCode,
                                        responseBody
                                );
                            }

                            if (statusCode.isError()) {
                                throw new NonRetryableExternalApiException(
                                        "Non-retryable HTTP response from " + dependencyName + ": " + statusCode.value(),
                                        statusCode,
                                        responseBody
                                );
                            }

                            return ResponseEntity
                                    .status(statusCode)
                                    .headers(clientResponse.getHeaders())
                                    .body(responseBody);
                        });
            } catch (ResourceAccessException ex) {
                logTransportFailure(dependencyName, correlationId, retryName, attempt, uri, startNanos, ex);
                throw new RetryableExternalApiException("Transport failure calling " + dependencyName, ex);
            } catch (IOException ex) {
                logTransportFailure(dependencyName, correlationId, retryName, attempt, uri, startNanos, ex);
                throw new RetryableExternalApiException("IO failure calling " + dependencyName, ex);
            } catch (RetryableExternalApiException | NonRetryableExternalApiException ex) {
                throw ex;
            } catch (Exception ex) {
                long durationMs = (System.nanoTime() - startNanos) / 1_000_000;

                if (log.isDebugEnabled()) {
                    log.debug(
                            "external_call_attempt_unexpected_failure dependency={} correlationId={} retryName={} attempt={} method=GET uri={} durationMs={} errorClass={} errorMessage={}",
                            dependencyName,
                            correlationId,
                            retryName,
                            attempt,
                            uri,
                            durationMs,
                            ex.getClass().getSimpleName(),
                            ex.getMessage(),
                            ex
                    );
                }

                throw ex;
            }
        });

        return supplier.get();
    }

    private void logTransportFailure(
            String dependencyName,
            String correlationId,
            String retryName,
            int attempt,
            String uri,
            long startNanos,
            Exception ex
    ) {
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;

        if (log.isDebugEnabled()) {
            log.debug(
                    "external_call_attempt_transport_failure dependency={} correlationId={} retryName={} attempt={} method=GET uri={} durationMs={} errorClass={} errorMessage={}",
                    dependencyName,
                    correlationId,
                    retryName,
                    attempt,
                    uri,
                    durationMs,
                    ex.getClass().getSimpleName(),
                    ex.getMessage(),
                    ex
            );
        }
    }

    private boolean isRetryableStatus(HttpStatusCode statusCode) {
        int value = statusCode.value();

        return statusCode.is5xxServerError()
                || value == 408
                || value == 429;
    }

    private String safeBody(String body) {
        if (body == null) {
            return null;
        }

        String sanitized = body
                .replaceAll("(?i)\"access_token\"\\s*:\\s*\"[^\"]+\"", "\"access_token\":\"***\"")
                .replaceAll("(?i)\"refresh_token\"\\s*:\\s*\"[^\"]+\"", "\"refresh_token\":\"***\"")
                .replaceAll("(?i)\"password\"\\s*:\\s*\"[^\"]+\"", "\"password\":\"***\"")
                .replaceAll("(?i)\"secret\"\\s*:\\s*\"[^\"]+\"", "\"secret\":\"***\"")
                .replaceAll("(?i)\"apiKey\"\\s*:\\s*\"[^\"]+\"", "\"apiKey\":\"***\"");

        int maxLength = 2_000;
        return sanitized.length() <= maxLength ? sanitized : sanitized.substring(0, maxLength) + "...[truncated]";
    }
}
