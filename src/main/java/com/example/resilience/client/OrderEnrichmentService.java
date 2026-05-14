package com.example.resilience.client;

import com.example.resilience.client.dto.CustomerDto;
import com.example.resilience.client.dto.InventoryDto;
import com.example.resilience.client.dto.OrderDto;
import com.example.resilience.client.dto.PricingDto;
import com.example.resilience.core.CorrelationIdContext;
import com.example.resilience.core.ResilientApiClientFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

/**
 * Example orchestrator: four external API calls in sequence, each deserialized into a domain
 * record DTO, each followed by a small domain business rule, and each feeding the next call.
 * This is the adoption template: a domain service owns the chaining logic, while every
 * {@code factory.forDependency(...).get(uri, Dto.class)} call independently retries according
 * to its configured {@code max-attempts}.
 *
 * <p>One correlation id is established for the whole chain so every per-attempt log line across
 * all four dependencies can be correlated. If any step exhausts its retries, the chain fails
 * fast with the retryable exception from the executor.
 */
@Service
@RequiredArgsConstructor
public class OrderEnrichmentService {

    private final ResilientApiClientFactory factory;

    public InventoryDto enrichOrder(String orderId) {
        String existingCorrelationId = CorrelationIdContext.get();
        boolean createdCorrelationId = existingCorrelationId == null || existingCorrelationId.isBlank();
        CorrelationIdContext.getOrCreate();

        try {
            OrderDto order = factory.forDependency("step1-api")
                    .get("/orders/" + encode(orderId), OrderDto.class);
            require(order.customerId() != null, "order " + orderId + " has no customer");

            CustomerDto customer = factory.forDependency("step2-api")
                    .get("/customers/" + encode(order.customerId()), CustomerDto.class);
            require(customer.pricingTier() != null, "customer " + customer.customerId() + " has no pricing tier");

            PricingDto pricing = factory.forDependency("step3-api")
                    .get("/pricing/" + encode(customer.pricingTier()), PricingDto.class);

            InventoryDto inventory = factory.forDependency("step4-api")
                    .get("/inventory/" + encode(pricing.skuId()), InventoryDto.class);
            require(inventory.availableUnits() > 0, "SKU " + inventory.skuId() + " is out of stock");

            return inventory;
        } finally {
            if (createdCorrelationId) {
                CorrelationIdContext.clear();
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static String encode(String value) {
        return UriUtils.encodePathSegment(value, StandardCharsets.UTF_8);
    }
}
