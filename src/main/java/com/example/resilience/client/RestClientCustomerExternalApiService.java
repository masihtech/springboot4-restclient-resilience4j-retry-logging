package com.example.resilience.client;

import com.example.resilience.client.dto.CustomerDto;
import com.example.resilience.core.ResilientApiClientFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
class RestClientCustomerExternalApiService implements CustomerExternalApiService {

    private final ResilientApiClientFactory factory;

    @Override
    public CustomerDto getCustomer(String customerId) {
        CustomerDto customer = factory.forDependency("step2-api")
                .get("/customers/" + ExternalApiUris.encodePathSegment(customerId), CustomerDto.class);
        require(StringUtils.hasText(customer.pricingTier()),
                "customer " + customer.customerId() + " has no pricing tier");
        return customer;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
