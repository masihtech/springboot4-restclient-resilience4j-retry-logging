package com.example.resilience.core;

import com.example.resilience.config.ExternalDependenciesProperties;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResilientApiClientFactoryTest {

    private ResilientApiClientFactory factory(Map<String, RestClient> restClients,
                                              ExternalDependenciesProperties props) {
        return new ResilientApiClientFactory(
                new ExternalRestClients(restClients),
                Mockito.mock(ResilientRestClientExecutor.class),
                Mockito.mock(CircuitBreakerFactory.class),
                props,
                new tools.jackson.databind.ObjectMapper());
    }

    private ExternalDependenciesProperties propsWith(String name, String circuitBreaker) {
        ExternalDependenciesProperties.Dependency dep = new ExternalDependenciesProperties.Dependency();
        dep.setBaseUrl("https://x");
        dep.setCircuitBreaker(circuitBreaker);
        ExternalDependenciesProperties props = new ExternalDependenciesProperties();
        props.setDependencies(new LinkedHashMap<>(Map.of(name, dep)));
        return props;
    }

    @Test
    void cachesClientPerDependency() {
        var props = propsWith("step1-api", "step1Cb");
        var factory = factory(Map.of("step1-api", Mockito.mock(RestClient.class)), props);

        ResilientApiClient first = factory.forDependency("step1-api");
        ResilientApiClient second = factory.forDependency("step1-api");

        assertThat(first).isSameAs(second);
    }

    @Test
    void throwsForDependencyNotInProperties() {
        var props = propsWith("step1-api", "step1Cb");
        var factory = factory(Map.of("step1-api", Mockito.mock(RestClient.class)), props);

        assertThatThrownBy(() -> factory.forDependency("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    void throwsWhenNoRestClientRegistered() {
        var props = propsWith("step1-api", "step1Cb");
        var factory = factory(Map.of(), props);

        assertThatThrownBy(() -> factory.forDependency("step1-api"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RestClient");
    }
}
