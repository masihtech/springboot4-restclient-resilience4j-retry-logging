package com.example.resilience.client;

import com.example.resilience.client.dto.CustomerDto;
import com.example.resilience.client.dto.InventoryDto;
import com.example.resilience.client.dto.OrderDto;
import com.example.resilience.client.dto.PricingDto;
import com.example.resilience.core.ApiRequest;
import com.example.resilience.core.CorrelationIdContext;
import com.example.resilience.core.ResilientApiClientFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Example orchestrator: four external API calls in sequence, each deserialized into a domain
 * record DTO, each followed by a small domain business rule, and each feeding the next call.
 * This is the adoption template — a domain service owns the chaining logic, while every
 * {@code factory.forDependency(...).get(uri, Dto.class)} call independently retries (per its
 * configured {@code max-attempts}) and is guarded by its own circuit breaker.
 *
 * <p>One correlation id is established for the whole chain so every per-attempt log line across
 * all four dependencies can be correlated. If any step exhausts its retries or its circuit
 * breaker is open, the chain fails fast with {@link ExternalApiUnavailableException}.
 */
@Service
@RequiredArgsConstructor
public class OrderEnrichmentService {

    private final ResilientApiClientFactory factory;

    public InventoryDto enrichOrder(String orderId) {
        CorrelationIdContext.getOrCreate();

        OrderDto order = factory.forDependency("step1-api")
                .get(ApiRequest.builder("/orders/{orderId}")
                        .uriVariable("orderId", orderId)
                        .queryParam("include", "customer")
                        .header("X-Client-Flow", "order-enrichment")
                        .build(), OrderDto.class);
        require(order.customerId() != null, "order " + orderId + " has no customer");

        CustomerDto customer = factory.forDependency("step2-api")
                .get(ApiRequest.builder("/orders/{orderId}/customers/{customerId}")
                        .uriVariable("orderId", orderId)
                        .uriVariable("customerId", order.customerId())
                        .queryParam("fields", "pricingTier")
                        .header("X-Customer-Use-Case", "pricing")
                        .build(), CustomerDto.class);
        require(customer.pricingTier() != null, "customer " + customer.customerId() + " has no pricing tier");

        PricingDto pricing = factory.forDependency("step3-api")
                .get(ApiRequest.builder("/pricing/{pricingTier}")
                        .uriVariable("pricingTier", customer.pricingTier())
                        .queryParam("currency", "USD")
                        .header("X-Pricing-Mode", "current")
                        .build(), PricingDto.class);

        InventoryDto inventory = factory.forDependency("step4-api")
                .get(ApiRequest.builder("/pricing/{pricingTier}/inventory/{skuId}")
                        .uriVariable("pricingTier", pricing.pricingTier())
                        .uriVariable("skuId", pricing.skuId())
                        .queryParam("warehouse", "primary")
                        .queryParam("include", "availability", "lead-time")
                        .header("X-Inventory-Scope", "regional")
                        .build(), InventoryDto.class);
        require(inventory.availableUnits() > 0, "SKU " + inventory.skuId() + " is out of stock");

        return inventory;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
