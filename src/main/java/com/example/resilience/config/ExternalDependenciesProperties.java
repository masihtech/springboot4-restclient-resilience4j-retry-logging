package com.example.resilience.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Externalized configuration for every outbound HTTP dependency, keyed by a logical
 * dependency name (e.g. {@code step1-api}). Each entry drives one {@code RestClient}
 * and one Resilience4j retry instance.
 */
@Validated
@ConfigurationProperties(prefix = "external")
@Getter
@Setter
public class ExternalDependenciesProperties {

    @Valid
    private Map<String, Dependency> dependencies = new LinkedHashMap<>();

    public Dependency getRequired(String name) {
        Dependency dependency = dependencies.get(name);
        if (dependency == null) {
            throw new IllegalArgumentException(
                    "No external dependency configured under 'external.dependencies." + name + "'. "
                            + "Known dependencies: " + dependencies.keySet());
        }
        return dependency;
    }

    @Getter
    @Setter
    public static class Dependency {

        @NotBlank
        private String baseUrl;

        private Duration connectTimeout = Duration.ofSeconds(2);

        private Duration readTimeout = Duration.ofSeconds(5);

        private Duration connectionRequestTimeout = Duration.ofSeconds(2);

        @Min(1)
        private int maxConnections = 20;

        private String sslBundle;

        private Map<String, String> defaultHeaders = new LinkedHashMap<>(Map.of("Accept", "application/json"));

        @Valid
        private Retry retry = new Retry();

    }

    @Getter
    @Setter
    public static class Retry {

        @Min(1)
        private int maxAttempts = 4;

        private Duration initialBackoff = Duration.ofMillis(500);

        @DecimalMin("1.0")
        private double backoffMultiplier = 2.0;

        @PositiveOrZero
        private double jitterFactor = 0.0;

    }
}
