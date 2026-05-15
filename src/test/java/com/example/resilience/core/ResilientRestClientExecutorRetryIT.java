package com.example.resilience.core;

import com.example.resilience.client.NonRetryableExternalApiException;
import com.example.resilience.client.RetryableExternalApiException;
import com.example.resilience.support.TestSupport;
import io.github.resilience4j.retry.RetryRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResilientRestClientExecutorRetryIT {

    private static final String RETRY = "test-api";
    private static final int MAX_ATTEMPTS = 4;

    private MockWebServer server;
    private ResilientRestClientExecutor executor;
    private RestClient restClient;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        RetryRegistry registry = TestSupport.retryRegistry(RETRY, MAX_ATTEMPTS, Duration.ofMillis(10));
        executor = new ResilientRestClientExecutor(registry);
        restClient = TestSupport.restClient(server.url("/").toString(),
                Duration.ofMillis(500), Duration.ofMillis(300));
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void succeedsOnSecondAttemptAfterRetryableStatus() {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("{\"error\":\"unavailable\"}"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"id\":\"123\"}"));

        ResponseEntity<String> response = executor.executeGet(restClient, RETRY, "test-api", "/resource");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("{\"id\":\"123\"}");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void exhaustsRetriesThenThrowsRetryableException() {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            server.enqueue(new MockResponse().setResponseCode(503).setBody("down"));
        }

        assertThatThrownBy(() -> executor.executeGet(restClient, RETRY, "test-api", "/resource"))
                .isInstanceOf(RetryableExternalApiException.class);
        assertThat(server.getRequestCount()).isEqualTo(MAX_ATTEMPTS);
    }

    @Test
    void doesNotRetryNonRetryableClientError() {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("bad request"));

        assertThatThrownBy(() -> executor.executeGet(restClient, RETRY, "test-api", "/resource"))
                .isInstanceOf(NonRetryableExternalApiException.class);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 500, 502, 504})
    void retriesOtherRetryableStatuses(int status) {
        server.enqueue(new MockResponse().setResponseCode(status).setBody("retry me"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        ResponseEntity<String> response = executor.executeGet(restClient, RETRY, "test-api", "/resource");

        assertThat(response.getBody()).isEqualTo("ok");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void expandsUriVariablesQueryParamsAndHeadersOnEveryAttempt() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("retry me"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        ApiRequest request = ApiRequest.builder("/accounts/{accountId}/orders/{orderId}")
                .uriVariable("accountId", "A1")
                .uriVariable("orderId", "O2")
                .queryParam("include", "items")
                .queryParam("status", "OPEN", "PENDING")
                .header("X-Tenant-Id", "tenant-1")
                .header("X-Api-Version", "2026-05")
                .build();

        ResponseEntity<String> response = executor.executeGet(restClient, RETRY, "test-api", request);

        assertThat(response.getBody()).isEqualTo("ok");
        assertThat(server.getRequestCount()).isEqualTo(2);

        RecordedRequest first = server.takeRequest();
        RecordedRequest second = server.takeRequest();
        assertThat(first.getPath())
                .isEqualTo("/accounts/A1/orders/O2?include=items&status=OPEN&status=PENDING");
        assertThat(second.getPath()).isEqualTo(first.getPath());
        assertThat(first.getHeader("X-Tenant-Id")).isEqualTo("tenant-1");
        assertThat(second.getHeader("X-Tenant-Id")).isEqualTo("tenant-1");
        assertThat(first.getHeader("X-Api-Version")).isEqualTo("2026-05");
    }

    @Test
    void retriesTransportFailure() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("recovered"));

        ResponseEntity<String> response = executor.executeGet(restClient, RETRY, "test-api", "/resource");

        assertThat(response.getBody()).isEqualTo("recovered");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void retriesReadTimeout() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("slow")
                .setBodyDelay(2, TimeUnit.SECONDS));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("fast"));

        ResponseEntity<String> response = executor.executeGet(restClient, RETRY, "test-api", "/resource");

        assertThat(response.getBody()).isEqualTo("fast");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void appliesExponentialBackoffBetweenAttempts() {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            server.enqueue(new MockResponse().setResponseCode(503).setBody("down"));
        }

        long start = System.nanoTime();
        assertThatThrownBy(() -> executor.executeGet(restClient, RETRY, "test-api", "/resource"))
                .isInstanceOf(RetryableExternalApiException.class);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // 3 waits after 4 attempts: 10ms + 20ms + 40ms = 70ms minimum.
        assertThat(elapsedMs).isGreaterThanOrEqualTo(70);
    }
}
