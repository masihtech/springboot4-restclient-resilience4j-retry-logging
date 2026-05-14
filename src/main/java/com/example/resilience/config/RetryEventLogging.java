package com.example.resilience.config;

import io.github.resilience4j.retry.Retry;
import lombok.extern.slf4j.Slf4j;

/**
 * Registers DEBUG-level lifecycle logging on a Resilience4j {@link Retry} instance. Extracted so
 * it can be reused both by {@link RetryEventLoggingConfig} at startup and by tests. Listeners run
 * synchronously on the calling thread, so the MDC correlation id is present in every event log.
 */
@Slf4j
public final class RetryEventLogging {

    private RetryEventLogging() {
    }

    public static void register(Retry retry) {
        retry.getEventPublisher()
                .onRetry(event -> {
                    if (log.isDebugEnabled()) {
                        log.debug(
                                "retry_scheduled retryName={} failedAttempt={} nextAttempt={} waitMs={} errorClass={} errorMessage={}",
                                event.getName(),
                                event.getNumberOfRetryAttempts(),
                                event.getNumberOfRetryAttempts() + 1,
                                event.getWaitInterval().toMillis(),
                                event.getLastThrowable() == null ? null : event.getLastThrowable().getClass().getSimpleName(),
                                event.getLastThrowable() == null ? null : event.getLastThrowable().getMessage()
                        );
                    }
                })
                .onSuccess(event -> {
                    if (log.isDebugEnabled()) {
                        log.debug("retry_success retryName={} retryAttempts={}", event.getName(), event.getNumberOfRetryAttempts());
                    }
                })
                .onError(event -> {
                    if (log.isDebugEnabled()) {
                        log.debug(
                                "retry_exhausted retryName={} retryAttempts={} errorClass={} errorMessage={}",
                                event.getName(),
                                event.getNumberOfRetryAttempts(),
                                event.getLastThrowable() == null ? null : event.getLastThrowable().getClass().getSimpleName(),
                                event.getLastThrowable() == null ? null : event.getLastThrowable().getMessage()
                        );
                    }
                })
                .onIgnoredError(event -> {
                    if (log.isDebugEnabled()) {
                        log.debug(
                                "retry_ignored_error retryName={} attempts={} errorClass={} errorMessage={}",
                                event.getName(),
                                event.getNumberOfRetryAttempts(),
                                event.getLastThrowable() == null ? null : event.getLastThrowable().getClass().getSimpleName(),
                                event.getLastThrowable() == null ? null : event.getLastThrowable().getMessage()
                        );
                    }
                });
    }
}
