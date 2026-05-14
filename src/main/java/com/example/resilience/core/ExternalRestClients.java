package com.example.resilience.core;

import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Holds the per-dependency {@link RestClient} instances keyed by logical dependency name.
 * A dedicated wrapper type (rather than a raw {@code Map<String, RestClient>} bean) avoids
 * Spring's "collect every bean of type T" behavior for {@code Map} injection points.
 */
public record ExternalRestClients(Map<String, RestClient> byName) {

    public RestClient get(String dependencyName) {
        RestClient client = byName.get(dependencyName);
        if (client == null) {
            throw new IllegalArgumentException("No RestClient registered for external dependency: " + dependencyName);
        }
        return client;
    }
}
