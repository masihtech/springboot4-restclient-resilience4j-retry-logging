package com.example.resilience.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdContextTest {

    @AfterEach
    void cleanup() {
        CorrelationIdContext.clear();
    }

    @Test
    void getOrCreateGeneratesWhenAbsent() {
        assertThat(CorrelationIdContext.get()).isNull();
        String created = CorrelationIdContext.getOrCreate();
        assertThat(created).isNotBlank();
        assertThat(CorrelationIdContext.get()).isEqualTo(created);
    }

    @Test
    void getOrCreateReturnsExisting() {
        CorrelationIdContext.set("fixed-id");
        assertThat(CorrelationIdContext.getOrCreate()).isEqualTo("fixed-id");
    }

    @Test
    void setBlankClearsTheValue() {
        CorrelationIdContext.set("something");
        CorrelationIdContext.set("  ");
        assertThat(CorrelationIdContext.get()).isNull();
    }

    @Test
    void clearRemovesTheValue() {
        CorrelationIdContext.set("something");
        CorrelationIdContext.clear();
        assertThat(CorrelationIdContext.get()).isNull();
    }

    @Test
    void valueIsIsolatedPerThread() throws Exception {
        CorrelationIdContext.set("main-thread");
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<String> other = executor.submit(CorrelationIdContext::get);
            assertThat(other.get()).isNull();
        }
        assertThat(CorrelationIdContext.get()).isEqualTo("main-thread");
    }
}
