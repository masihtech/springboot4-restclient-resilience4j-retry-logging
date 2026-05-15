package com.example.resilience.client;

import com.example.resilience.client.dto.CustomerDto;
import com.example.resilience.core.ApiRequest;
import com.example.resilience.core.ResilientApiClientFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
class RestClientCustomerExternalApiService implements CustomerExternalApiService {

    private final ResilientApiClientFactory factory;

    @Override
    public CustomerDto getCustomer(String orderId, String customerId) {
        CustomerDto customer = factory.forDependency("step2-api")
                .get(ApiRequest.builder("/orders/{orderId}/customers/{customerId}")
                        .uriVariable("orderId", orderId)
                        .uriVariable("customerId", customerId)
                        .queryParam("fields", "pricingTier")
                        .header("X-Customer-Use-Case", "pricing")
                        .build(), CustomerDto.class);
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
