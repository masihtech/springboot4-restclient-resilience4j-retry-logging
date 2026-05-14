package com.example.resilience.client.dto;

/** Domain DTO for the step4-api inventory response. */
public record InventoryDto(String skuId, int availableUnits) {
}
