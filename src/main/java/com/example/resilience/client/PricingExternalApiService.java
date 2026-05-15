package com.example.resilience.client;

import com.example.resilience.client.dto.PricingDto;

/** Domain boundary for the step3 pricing external API. */
public interface PricingExternalApiService {

    PricingDto getPricing(String pricingTier);
}
