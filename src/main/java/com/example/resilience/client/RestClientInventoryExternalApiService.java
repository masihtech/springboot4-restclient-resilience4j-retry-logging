package com.example.resilience.client;

import com.example.resilience.client.dto.InventoryDto;
import com.example.resilience.core.ApiRequest;
import com.example.resilience.core.ResilientApiClientFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class RestClientInventoryExternalApiService implements InventoryExternalApiService {

    private final ResilientApiClientFactory factory;

    @Override
    public InventoryDto getInventory(String pricingTier, String skuId) {
        InventoryDto inventory = factory.forDependency("step4-api")
                .get(ApiRequest.builder("/pricing/{pricingTier}/inventory/{skuId}")
                        .uriVariable("pricingTier", pricingTier)
                        .uriVariable("skuId", skuId)
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
