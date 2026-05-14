package com.example.resilience.client;

import com.example.resilience.client.dto.InventoryDto;
import com.example.resilience.core.ResilientApiClientFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class RestClientInventoryExternalApiService implements InventoryExternalApiService {

    private final ResilientApiClientFactory factory;

    @Override
    public InventoryDto getInventory(String skuId) {
        InventoryDto inventory = factory.forDependency("step4-api")
                .get("/inventory/" + ExternalApiUris.encodePathSegment(skuId), InventoryDto.class);
        require(inventory.availableUnits() > 0, "SKU " + inventory.skuId() + " is out of stock");
        return inventory;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
