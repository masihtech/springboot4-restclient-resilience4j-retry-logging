package com.example.resilience.core;

import com.example.resilience.config.ExternalDependenciesProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single injection point for domain clients. Resolves the {@link org.springframework.web.client.RestClient},
 * validates the logical dependency exists in configuration, and caches one {@link ResilientApiClient}
 * per dependency. By convention the retry instance name equals the dependency name.
 */
@Component
@RequiredArgsConstructor
public class ResilientApiClientFactory {

    private final ExternalRestClients restClients;
    private final ResilientRestClientExecutor executor;
    private final ExternalDependenciesProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<String, ResilientApiClient> cache = new ConcurrentHashMap<>();

    public ResilientApiClient forDependency(String name) {
        return cache.computeIfAbsent(name, this::build);
    }

    private ResilientApiClient build(String name) {
        properties.getRequired(name);
        return new ResilientApiClient(name, restClients.get(name), executor, objectMapper);
    }
}
