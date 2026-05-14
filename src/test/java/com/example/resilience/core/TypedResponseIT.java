package com.example.resilience.core;

import com.example.resilience.client.dto.InventoryDto;
import com.example.resilience.support.TestSupport;
import io.github.resilience4j.retry.RetryRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the typed {@code get} overloads on {@link ResilientApiClient}: JSON responses are
 * deserialized into record DTOs after the resilient call returns. Wired without a circuit
 * breaker (null name) so the factory is unnecessary.
 */
class TypedResponseIT {

    private MockWebServer server;
    private ResilientApiClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        RetryRegistry registry = TestSupport.retryRegistry("step4-api", 4, Duration.ofMillis(10));
        ResilientRestClientExecutor executor = new ResilientRestClientExecutor(registry);
        client = new ResilientApiClient(
                "step4-api",
                TestSupport.restClient(server.url("/").toString(), Duration.ofMillis(500), Duration.ofMillis(300)),
                executor,
                null,
                null,
                new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void deserializesJsonIntoRecordDto() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"skuId\":\"S1\",\"availableUnits\":7}"));

        InventoryDto dto = client.get("/inventory/S1", InventoryDto.class);

        assertThat(dto.skuId()).isEqualTo("S1");
        assertThat(dto.availableUnits()).isEqualTo(7);
    }

    @Test
    void deserializesJsonArrayViaTypeReference() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("[{\"skuId\":\"S1\",\"availableUnits\":1},{\"skuId\":\"S2\",\"availableUnits\":2}]"));

        List<InventoryDto> dtos = client.get("/inventory", new TypeReference<List<InventoryDto>>() {
        });

        assertThat(dtos).hasSize(2);
        assertThat(dtos.get(1).skuId()).isEqualTo("S2");
    }

    @Test
    void wrapsDeserializationFailureWithDependencyName() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("not json at all"));

        assertThatThrownBy(() -> client.get("/inventory/S1", InventoryDto.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("step4-api");
    }
}
