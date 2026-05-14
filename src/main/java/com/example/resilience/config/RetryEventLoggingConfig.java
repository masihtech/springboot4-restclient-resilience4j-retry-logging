package com.example.resilience.config;

import io.github.resilience4j.retry.RetryRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Attaches retry lifecycle logging to every configured dependency's retry instance at startup.
 */
@Component
@RequiredArgsConstructor
public class RetryEventLoggingConfig {

    private final RetryRegistry retryRegistry;
    private final ExternalDependenciesProperties properties;

    @PostConstruct
    void registerRetryEventLogging() {
        properties.getDependencies().keySet()
                .forEach(name -> RetryEventLogging.register(retryRegistry.retry(name)));
    }
}
