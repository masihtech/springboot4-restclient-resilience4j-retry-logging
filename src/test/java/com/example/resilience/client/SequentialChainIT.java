package com.example.resilience.client;

import com.example.resilience.client.dto.InventoryDto;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test of the four-call sequential chain: each step feeds the next, each step retries
 * independently. Backoffs are shrunk to keep the test fast.
 */
@SpringBootTest
class SequentialChainIT {

    private static final MockWebServer step1 = new MockWebServer();
    private static final MockWebServer step2 = new MockWebServer();
    private static final MockWebServer step3 = new MockWebServer();
    private static final MockWebServer step4 = new MockWebServer();

    @Autowired
    private OrderEnrichmentService service;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        step1.start();
        step2.start();
        step3.start();
        step4.start();
        bind(registry, "step1-api", step1);
        bind(registry, "step2-api", step2);
        bind(registry, "step3-api", step3);
        bind(registry, "step4-api", step4);
    }

    private static void bind(DynamicPropertyRegistry registry, String name, MockWebServer server) {
        registry.add("external.dependencies." + name + ".base-url", () -> server.url("/").toString());
        registry.add("external.dependencies." + name + ".retry.initial-backoff", () -> "10ms");
    }

    @AfterAll
    static void shutdown() throws IOException {
        step1.shutdown();
        step2.shutdown();
        step3.shutdown();
        step4.shutdown();
    }

    @Test
    void runsAllFourCallsInSequenceFeedingEachIntoTheNext() {
        int b1 = step1.getRequestCount();
        int b2 = step2.getRequestCount();
        int b3 = step3.getRequestCount();
        int b4 = step4.getRequestCount();

        step1.enqueue(json("{\"orderId\":\"O1\",\"customerId\":\"C1\"}"));
        step2.enqueue(json("{\"customerId\":\"C1\",\"pricingTier\":\"P1\"}"));
        step3.enqueue(json("{\"pricingTier\":\"P1\",\"skuId\":\"S1\"}"));
        step4.enqueue(json("{\"skuId\":\"S1\",\"availableUnits\":7}"));

        InventoryDto result = service.enrichOrder("O1");

        assertThat(result.skuId()).isEqualTo("S1");
        assertThat(result.availableUnits()).isEqualTo(7);
        assertThat(step1.getRequestCount() - b1).isEqualTo(1);
        assertThat(step2.getRequestCount() - b2).isEqualTo(1);
        assertThat(step3.getRequestCount() - b3).isEqualTo(1);
        assertThat(step4.getRequestCount() - b4).isEqualTo(1);
    }

    @Test
    void retriesAFailingStepWithoutReplayingEarlierSteps() {
        int b1 = step1.getRequestCount();
        int b2 = step2.getRequestCount();
        int b3 = step3.getRequestCount();
        int b4 = step4.getRequestCount();

        step1.enqueue(json("{\"orderId\":\"O2\",\"customerId\":\"C2\"}"));
        step2.enqueue(json("{\"customerId\":\"C2\",\"pricingTier\":\"P2\"}"));
        step3.enqueue(new MockResponse().setResponseCode(503).setBody("down"));
        step3.enqueue(json("{\"pricingTier\":\"P2\",\"skuId\":\"S2\"}"));
        step4.enqueue(json("{\"skuId\":\"S2\",\"availableUnits\":3}"));

        InventoryDto result = service.enrichOrder("O2");

        assertThat(result.skuId()).isEqualTo("S2");
        assertThat(result.availableUnits()).isEqualTo(3);
        assertThat(step1.getRequestCount() - b1).isEqualTo(1);
        assertThat(step2.getRequestCount() - b2).isEqualTo(1);
        assertThat(step3.getRequestCount() - b3).isEqualTo(2); // one failure + one success
        assertThat(step4.getRequestCount() - b4).isEqualTo(1);
    }

    private static MockResponse json(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
