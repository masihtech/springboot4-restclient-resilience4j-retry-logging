package com.example.resilience.client;

import com.example.resilience.client.dto.OrderDto;

/** Domain boundary for the step1 order external API. */
public interface OrderExternalApiService {

    OrderDto getOrder(String orderId);
}
