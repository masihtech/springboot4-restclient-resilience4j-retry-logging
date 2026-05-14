package com.example.resilience.config;

import com.example.resilience.client.NonRetryableExternalApiException;
import com.example.resilience.client.RetryableExternalApiException;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds one Resilience4j {@link RetryConfig} per configured dependency. By convention the
 * retry instance name equals the dependency name, so {@code ResilientApiClient} can resolve
 * its retry instance from the dependency name alone.
 */
@Configuration
public class ResilienceRetryConfig {

    @Bean
    public RetryRegistry retryRegistry(ExternalDependenciesProperties properties) {
        Map<String, RetryConfig> configs = new LinkedHashMap<>();

        properties.getDependencies().forEach((name, dependency) -> {
            ExternalDependenciesProperties.Retry retry = dependency.getRetry();

            IntervalFunction intervalFunction = retry.getJitterFactor() > 0
                    ? IntervalFunction.ofExponentialRandomBackoff(
                            retry.getInitialBackoff(), retry.getBackoffMultiplier(), retry.getJitterFactor())
                    : IntervalFunction.ofExponentialBackoff(
                            retry.getInitialBackoff(), retry.getBackoffMultiplier());

            RetryConfig config = RetryConfig.custom()
                    .maxAttempts(retry.getMaxAttempts())
                    .intervalFunction(intervalFunction)
                    .retryExceptions(RetryableExternalApiException.class)
                    .ignoreExceptions(NonRetryableExternalApiException.class)
                    .build();

            configs.put(name, config);
        });

        RetryRegistry registry = RetryRegistry.of(configs);
        // Materialize each Retry under its named config so a later retry(name) lookup
        // returns the dependency-specific instance rather than a default-config one.
        configs.keySet().forEach(name -> registry.retry(name, name));
        return registry;
    }
}
