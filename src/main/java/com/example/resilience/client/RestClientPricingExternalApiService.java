package com.example.resilience.client;

import com.example.resilience.client.dto.PricingDto;
import com.example.resilience.core.ResilientApiClientFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
class RestClientPricingExternalApiService implements PricingExternalApiService {

    private final ResilientApiClientFactory factory;

    @Override
    public PricingDto getPricing(String pricingTier) {
        PricingDto pricing = factory.forDependency("step3-api")
                .get("/pricing/" + ExternalApiUris.encodePathSegment(pricingTier), PricingDto.class);
        require(StringUtils.hasText(pricing.skuId()), "pricing tier " + pricingTier + " has no SKU");
        return pricing;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
