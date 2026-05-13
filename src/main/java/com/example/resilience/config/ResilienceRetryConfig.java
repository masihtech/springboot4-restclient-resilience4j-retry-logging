package com.example.resilience.config;

import com.example.resilience.client.NonRetryableExternalApiException;
import com.example.resilience.client.RetryableExternalApiException;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;

@Configuration
public class ResilienceRetryConfig {

    @Bean
    public RetryRegistry retryRegistry() {
        RetryConfig externalApiRetryConfig = RetryConfig.custom()
                .maxAttempts(3)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofMillis(500), 2.0))
                .retryExceptions(RetryableExternalApiException.class)
                .ignoreExceptions(NonRetryableExternalApiException.class)
                .build();

        return RetryRegistry.of(Map.of("externalApiRetry", externalApiRetryConfig));
    }
}
