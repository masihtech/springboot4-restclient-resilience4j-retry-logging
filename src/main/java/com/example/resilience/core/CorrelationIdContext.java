package com.example.resilience.core;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * Thin wrapper over SLF4J {@link MDC} for the request correlation id. The id is stored on the
 * calling thread; Resilience4j {@code Retry.decorateSupplier} runs synchronously on that thread,
 * so the id survives every retry attempt and is visible to retry event listeners without being
 * threaded through method signatures.
 */
public final class CorrelationIdContext {

    public static final String MDC_KEY = "correlationId";

    private CorrelationIdContext() {
    }

    /** Returns the current correlation id, or {@code null} if none is set. */
    public static String get() {
        return MDC.get(MDC_KEY);
    }

    /** Returns the current correlation id, generating and storing a new one if absent. */
    public static String getOrCreate() {
        String existing = MDC.get(MDC_KEY);
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        String generated = UUID.randomUUID().toString();
        MDC.put(MDC_KEY, generated);
        return generated;
    }

    public static void set(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            MDC.remove(MDC_KEY);
        } else {
            MDC.put(MDC_KEY, correlationId);
        }
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
