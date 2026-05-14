package com.example.resilience.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Externalized configuration for every outbound HTTP dependency, keyed by a logical
 * dependency name (e.g. {@code step1-api}). Each entry drives one {@code RestClient},
 * one Resilience4j retry instance, and optionally references a circuit breaker instance
 * declared under the {@code resilience4j.circuitbreaker} namespace.
 */
@Validated
@ConfigurationProperties(prefix = "external")
public class ExternalDependenciesProperties {

    @Valid
    private Map<String, Dependency> dependencies = new LinkedHashMap<>();

    public Map<String, Dependency> getDependencies() {
        return dependencies;
    }

    public void setDependencies(Map<String, Dependency> dependencies) {
        this.dependencies = dependencies;
    }

    public Dependency getRequired(String name) {
        Dependency dependency = dependencies.get(name);
        if (dependency == null) {
            throw new IllegalArgumentException(
                    "No external dependency configured under 'external.dependencies." + name + "'. "
                            + "Known dependencies: " + dependencies.keySet());
        }
        return dependency;
    }

    public static class Dependency {

        @NotBlank
        private String baseUrl;

        private Duration connectTimeout = Duration.ofSeconds(2);

        private Duration readTimeout = Duration.ofSeconds(5);

        private Map<String, String> defaultHeaders = new LinkedHashMap<>(Map.of("Accept", "application/json"));

        @Valid
        private Retry retry = new Retry();

        /** Name of a {@code resilience4j.circuitbreaker} instance; {@code null}/blank means no circuit breaker. */
        private String circuitBreaker;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        public Map<String, String> getDefaultHeaders() {
            return defaultHeaders;
        }

        public void setDefaultHeaders(Map<String, String> defaultHeaders) {
            this.defaultHeaders = defaultHeaders;
        }

        public Retry getRetry() {
            return retry;
        }

        public void setRetry(Retry retry) {
            this.retry = retry;
        }

        public String getCircuitBreaker() {
            return circuitBreaker;
        }

        public void setCircuitBreaker(String circuitBreaker) {
            this.circuitBreaker = circuitBreaker;
        }

        public boolean hasCircuitBreaker() {
            return circuitBreaker != null && !circuitBreaker.isBlank();
        }
    }

    public static class Retry {

        @Min(1)
        private int maxAttempts = 4;

        private Duration initialBackoff = Duration.ofMillis(500);

        @DecimalMin("1.0")
        private double backoffMultiplier = 2.0;

        @PositiveOrZero
        private double jitterFactor = 0.0;

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getInitialBackoff() {
            return initialBackoff;
        }

        public void setInitialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
        }

        public double getBackoffMultiplier() {
            return backoffMultiplier;
        }

        public void setBackoffMultiplier(double backoffMultiplier) {
            this.backoffMultiplier = backoffMultiplier;
        }

        public double getJitterFactor() {
            return jitterFactor;
        }

        public void setJitterFactor(double jitterFactor) {
            this.jitterFactor = jitterFactor;
        }
    }
}
