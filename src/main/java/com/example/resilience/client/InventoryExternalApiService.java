package com.example.resilience.client;

import com.example.resilience.client.dto.InventoryDto;

/** Domain boundary for the step4 inventory external API. */
public interface InventoryExternalApiService {

    InventoryDto getInventory(String skuId);
}
