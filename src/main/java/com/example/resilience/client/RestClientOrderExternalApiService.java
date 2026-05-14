package com.example.resilience.client;

import com.example.resilience.client.dto.OrderDto;
import com.example.resilience.core.ResilientApiClientFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
class RestClientOrderExternalApiService implements OrderExternalApiService {

    private final ResilientApiClientFactory factory;

    @Override
    public OrderDto getOrder(String orderId) {
        OrderDto order = factory.forDependency("step1-api")
                .get("/orders/" + ExternalApiUris.encodePathSegment(orderId), OrderDto.class);
        require(StringUtils.hasText(order.customerId()), "order " + orderId + " has no customer");
        return order;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
