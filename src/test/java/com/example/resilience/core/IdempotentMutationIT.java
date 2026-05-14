package com.example.resilience.core;

import com.example.resilience.support.TestSupport;
import io.github.resilience4j.retry.RetryRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotentMutationIT {

    private static final String RETRY = "test-api";

    private MockWebServer server;
    private ResilientRestClientExecutor executor;
    private RestClient restClient;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        RetryRegistry registry = TestSupport.retryRegistry(RETRY, 4, Duration.ofMillis(10));
        executor = new ResilientRestClientExecutor(registry);
        restClient = TestSupport.restClient(server.url("/").toString(),
                Duration.ofMillis(500), Duration.ofMillis(300));
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void forwardsIdempotencyKeyHeaderAndRetriesOnRetryableStatus() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("down"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("created"));

        ResponseEntity<String> response = executor.executeIdempotentMutation(
                restClient, RETRY, "test-api", HttpMethod.PUT, "/resource/1", "{\"name\":\"x\"}", "key-123");

        assertThat(response.getBody()).isEqualTo("created");
        assertThat(server.getRequestCount()).isEqualTo(2);

        RecordedRequest first = server.takeRequest();
        RecordedRequest second = server.takeRequest();
        assertThat(first.getHeader("Idempotency-Key")).isEqualTo("key-123");
        assertThat(second.getHeader("Idempotency-Key")).isEqualTo("key-123");
        assertThat(first.getMethod()).isEqualTo("PUT");
    }

    @Test
    void rejectsMissingIdempotencyKey() {
        assertThatThrownBy(() -> executor.executeIdempotentMutation(
                restClient, RETRY, "test-api", HttpMethod.PUT, "/resource/1", "{}", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotency key");
        assertThat(server.getRequestCount()).isZero();
    }
}
