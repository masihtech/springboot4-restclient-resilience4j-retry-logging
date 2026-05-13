package com.example.resilience.config;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RetryEventLoggingConfig {

    private final RetryRegistry retryRegistry;

    @PostConstruct
    void registerRetryEventLogging() {
        register("externalApiRetry");
    }

    private void register(String retryName) {
        Retry retry = retryRegistry.retry(retryName);

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
