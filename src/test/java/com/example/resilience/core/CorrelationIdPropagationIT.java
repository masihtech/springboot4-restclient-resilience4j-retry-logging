package com.example.resilience.core;

import com.example.resilience.config.RetryEventLogging;
import com.example.resilience.support.LogCapture;
import com.example.resilience.support.TestSupport;
import io.github.resilience4j.retry.RetryRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdPropagationIT {

    private static final String RETRY = "test-api";

    private MockWebServer server;
    private ResilientRestClientExecutor executor;
    private RestClient restClient;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        RetryRegistry registry = TestSupport.retryRegistry(RETRY, 4, Duration.ofMillis(10));
        RetryEventLogging.register(registry.retry(RETRY));
        executor = new ResilientRestClientExecutor(registry);
        restClient = TestSupport.restClient(server.url("/").toString(),
                Duration.ofMillis(500), Duration.ofMillis(300));
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
        CorrelationIdContext.clear();
    }

    @Test
    void correlationIdAppearsInEveryAttemptAndRetryEvent() {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("down"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        try (LogCapture logs = new LogCapture("com.example.resilience")) {
            CorrelationIdContext.set("corr-abc-123");
            executor.executeGet(restClient, RETRY, "test-api", "/resource");

            // Executor log lines carry the id as an explicit field.
            assertThat(logs.count("correlationId=corr-abc-123")).isGreaterThanOrEqualTo(4); // 2 started + 2 response
            // Retry event listeners run on the same thread, so the id is present via MDC.
            assertThat(logs.anyWithMdc("retry_scheduled", CorrelationIdContext.MDC_KEY, "corr-abc-123")).isTrue();
        }
    }

    @Test
    void correlationIdSurvivesOnAVirtualThread() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("down"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        try (LogCapture logs = new LogCapture("com.example.resilience");
             var vexec = Executors.newVirtualThreadPerTaskExecutor()) {
            vexec.submit(() -> {
                CorrelationIdContext.set("corr-virtual-9");
                executor.executeGet(restClient, RETRY, "test-api", "/resource");
            }).get();

            assertThat(logs.count("correlationId=corr-virtual-9")).isGreaterThanOrEqualTo(4);
        }
    }
}
