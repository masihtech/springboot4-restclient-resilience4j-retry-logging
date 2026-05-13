package com.example.resilience.client;

import lombok.RequiredArgsConstructor;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
public class CustomerApiClient {

    private final RestClient externalApiRestClient;
    private final ResilientRestClientExecutor executor;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;

    public String getCustomer(String customerId, String correlationId) {
        CircuitBreaker circuitBreaker = circuitBreakerFactory.create("customerApiCircuitBreaker");

        String encodedCustomerId = UriUtils.encodePathSegment(customerId, StandardCharsets.UTF_8);

        ResponseEntity<String> response = circuitBreaker.run(
                () -> executor.executeGet(
                        externalApiRestClient,
                        "externalApiRetry",
                        "customer-api",
                        correlationId,
                        "/customers/" + encodedCustomerId
                ),
                throwable -> fallback(customerId, correlationId, throwable)
        );

        return response.getBody();
    }

    private ResponseEntity<String> fallback(String customerId, String correlationId, Throwable throwable) {
        throw new ExternalApiUnavailableException(
                "Customer API unavailable. customerId=" + customerId + ", correlationId=" + correlationId,
                throwable
        );
    }
}
