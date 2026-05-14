package com.example.resilience.core;

import org.springframework.web.client.RestClient;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Holds the per-dependency {@link RestClient} instances keyed by logical dependency name.
 * A dedicated wrapper type (rather than a raw {@code Map<String, RestClient>} bean) avoids
 * Spring's "collect every bean of type T" behavior for {@code Map} injection points.
 */
public record ExternalRestClients(Map<String, RestClient> byName, List<Closeable> closeables) implements AutoCloseable {

    public ExternalRestClients {
        byName = Map.copyOf(byName);
        closeables = List.copyOf(closeables);
    }

    public ExternalRestClients(Map<String, RestClient> byName) {
        this(byName, List.of());
    }

    public RestClient get(String dependencyName) {
        RestClient client = byName.get(dependencyName);
        if (client == null) {
            throw new IllegalArgumentException("No RestClient registered for external dependency: " + dependencyName);
        }
        return client;
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        for (Closeable closeable : closeables) {
            try {
                closeable.close();
            } catch (IOException ex) {
                if (failure == null) {
                    failure = ex;
                } else {
                    failure.addSuppressed(ex);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
