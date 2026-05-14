package com.example.resilience.client;

import com.example.resilience.core.ResilientApiClient;
import com.example.resilience.core.ResilientApiClientFactory;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Drives the {@code step1-api} circuit breaker through CLOSED -> OPEN -> HALF_OPEN -> CLOSED
 * against a MockWebServer, using a shrunk sliding window so the transitions happen quickly.
 */
@SpringBootTest
class CircuitBreakerTransitionIT {

    private static final MockWebServer server = new MockWebServer();
    private static volatile int responseCode = 503;

    @Autowired
    private ResilientApiClientFactory factory;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setResponseCode(responseCode).setBody("body");
            }
        });
        server.start();

        registry.add("external.dependencies.step1-api.base-url", () -> server.url("/").toString());
        registry.add("external.dependencies.step1-api.retry.max-attempts", () -> "1");
        registry.add("external.dependencies.step1-api.retry.initial-backoff", () -> "10ms");

        String cb = "resilience4j.circuitbreaker.instances.step1ApiCircuitBreaker.";
        registry.add(cb + "sliding-window-type", () -> "count_based");
        registry.add(cb + "sliding-window-size", () -> "4");
        registry.add(cb + "minimum-number-of-calls", () -> "2");
        registry.add(cb + "failure-rate-threshold", () -> "50");
        registry.add(cb + "wait-duration-in-open-state", () -> "1s");
        registry.add(cb + "permitted-number-of-calls-in-half-open-state", () -> "2");
        registry.add(cb + "automatic-transition-from-open-to-half-open-enabled", () -> "false");
    }

    @AfterAll
    static void shutdown() throws IOException {
        server.shutdown();
    }

    @Test
    void transitionsThroughOpenAndHalfOpenBackToClosed() {
        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker("step1ApiCircuitBreaker");
        Set<CircuitBreaker.State> seenStates = ConcurrentHashMap.newKeySet();
        breaker.getEventPublisher()
                .onStateTransition(e -> seenStates.add(e.getStateTransition().getToState()));

        ResilientApiClient client = factory.forDependency("step1-api");
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // Continuous 503s trip the breaker open.
        responseCode = 503;
        for (int i = 0; i < 6; i++) {
            try {
                client.getEntity("/resource");
            } catch (RuntimeException ignored) {
                // expected: ExternalApiUnavailableException via fallback
            }
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // While OPEN the call short-circuits: the fallback fires without hitting the server.
        int requestsBefore = server.getRequestCount();
        assertThatThrownBy(() -> client.getEntity("/resource"))
                .isInstanceOf(ExternalApiUnavailableException.class);
        assertThat(server.getRequestCount()).isEqualTo(requestsBefore);

        // After the open-state window, trial calls succeed and the breaker closes again.
        responseCode = 200;
        await().atMost(5, SECONDS).pollInterval(200, MILLISECONDS).untilAsserted(() -> {
            try {
                client.getEntity("/resource");
            } catch (RuntimeException ignored) {
                // a probe may still be rejected until the window fully elapses
            }
            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        });

        assertThat(seenStates).contains(
                CircuitBreaker.State.OPEN,
                CircuitBreaker.State.HALF_OPEN,
                CircuitBreaker.State.CLOSED);
    }
}
