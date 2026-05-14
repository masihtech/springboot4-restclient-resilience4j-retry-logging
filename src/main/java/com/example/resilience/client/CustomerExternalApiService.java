package com.example.resilience.client;

import com.example.resilience.client.dto.CustomerDto;

/** Domain boundary for the step2 customer external API. */
public interface CustomerExternalApiService {

    CustomerDto getCustomer(String customerId);
}
