package com.example.resilience.support;

import com.example.resilience.client.NonRetryableExternalApiException;
import com.example.resilience.client.RetryableExternalApiException;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/** Shared wiring helpers for the manually-wired (non-Spring) integration tests. */
public final class TestSupport {

    private TestSupport() {
    }

    /** A retry registry holding a single named instance, materialized under its own config. */
    public static RetryRegistry retryRegistry(String name, int maxAttempts, Duration initialBackoff) {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(initialBackoff, 2.0))
                .retryExceptions(RetryableExternalApiException.class)
                .ignoreExceptions(NonRetryableExternalApiException.class)
                .build();
        RetryRegistry registry = RetryRegistry.of(Map.of(name, config));
        registry.retry(name, name);
        return registry;
    }

    public static RestClient restClient(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
