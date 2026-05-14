package com.example.resilience.config;

import com.example.resilience.core.ExternalRestClients;
import com.example.resilience.core.ResilientApiClient;
import com.example.resilience.core.ResilientApiClientFactory;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the full per-dependency wiring boots from {@code application.yml}: one RestClient and
 * one retry instance per configured dependency, and a usable factory.
 */
@SpringBootTest
class ExternalDependenciesContextIT {

    @Autowired
    private ExternalRestClients externalRestClients;

    @Autowired
    private RetryRegistry retryRegistry;

    @Autowired
    private ResilientApiClientFactory factory;

    @Autowired
    private ExternalDependenciesProperties properties;

    @Test
    void buildsOneRestClientPerDependency() {
        assertThat(externalRestClients.byName().keySet())
                .containsExactlyInAnyOrder("step1-api", "step2-api", "step3-api", "step4-api");
    }

    @Test
    void buildsOneRetryInstancePerDependency() {
        for (String name : properties.getDependencies().keySet()) {
            assertThat(retryRegistry.retry(name).getRetryConfig().getMaxAttempts()).isEqualTo(4);
        }
    }

    @Test
    void factoryResolvesEveryConfiguredDependency() {
        for (String name : properties.getDependencies().keySet()) {
            ResilientApiClient client = factory.forDependency(name);
            assertThat(client).isNotNull();
            assertThat(factory.forDependency(name)).isSameAs(client);
        }
    }
}
