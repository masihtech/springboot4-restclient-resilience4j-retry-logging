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

import static org.assertj.core.api.Assertions.assertThat;

class PerAttemptLoggingIT {

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
    }

    @Test
    void logsEveryAttemptAndRetryLifecycle() {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("down"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        try (LogCapture logs = new LogCapture("com.example.resilience")) {
            executor.executeGet(restClient, RETRY, "test-api", "/resource");

            assertThat(logs.count("external_call_attempt_started")).isEqualTo(2);
            assertThat(logs.count("external_call_attempt_response")).isEqualTo(2);
            assertThat(logs.any("retry_scheduled")).isTrue();
            assertThat(logs.any("retry_success")).isTrue();
        }
    }

    @Test
    void sanitizesSecretsInLoggedResponseBody() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"access_token\":\"super-secret-value\"}"));

        try (LogCapture logs = new LogCapture("com.example.resilience")) {
            executor.executeGet(restClient, RETRY, "test-api", "/resource");

            assertThat(logs.any("super-secret-value")).isFalse();
            assertThat(logs.any("\"access_token\":\"***\"")).isTrue();
        }
    }
}
