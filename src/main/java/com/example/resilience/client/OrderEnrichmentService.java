package com.example.resilience.client;

import com.example.resilience.client.dto.CustomerDto;
import com.example.resilience.client.dto.InventoryDto;
import com.example.resilience.client.dto.OrderDto;
import com.example.resilience.client.dto.PricingDto;
import com.example.resilience.core.CorrelationIdContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Example orchestrator: four external API services in sequence, each owning its own response
 * DTO mapping and domain rule before returning data to the chain. This is the adoption template:
 * the orchestration service owns flow, while API-specific services own external contracts.
 *
 * <p>One correlation id is established for the whole chain so every per-attempt log line across
 * all four dependencies can be correlated. If any step exhausts its retries, the chain fails
 * fast with the retryable exception from the executor.
 */
@Service
@RequiredArgsConstructor
public class OrderEnrichmentService {

    private final OrderExternalApiService orderService;
    private final CustomerExternalApiService customerService;
    private final PricingExternalApiService pricingService;
    private final InventoryExternalApiService inventoryService;

    public InventoryDto enrichOrder(String orderId) {
        String existingCorrelationId = CorrelationIdContext.get();
        boolean createdCorrelationId = existingCorrelationId == null || existingCorrelationId.isBlank();
        CorrelationIdContext.getOrCreate();

        try {
            OrderDto order = orderService.getOrder(orderId);
            CustomerDto customer = customerService.getCustomer(orderId, order.customerId());
            PricingDto pricing = pricingService.getPricing(customer.pricingTier());
            return inventoryService.getInventory(pricing.pricingTier(), pricing.skuId());
        } finally {
            if (createdCorrelationId) {
                CorrelationIdContext.clear();
            }
        }
    }
}
