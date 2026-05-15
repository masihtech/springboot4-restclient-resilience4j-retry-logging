package com.example.resilience.client;

import lombok.Getter;
import org.springframework.http.HttpStatusCode;

@Getter
public class NonRetryableExternalApiException extends RuntimeException {

    private final HttpStatusCode statusCode;
    private final String responseBody;

    public NonRetryableExternalApiException(String message, HttpStatusCode statusCode, String responseBody) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

}
