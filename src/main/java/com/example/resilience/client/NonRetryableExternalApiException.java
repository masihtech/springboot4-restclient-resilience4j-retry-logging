package com.example.resilience.client;

import org.springframework.http.HttpStatusCode;

public class NonRetryableExternalApiException extends RuntimeException {

    private final HttpStatusCode statusCode;
    private final String responseBody;

    public NonRetryableExternalApiException(String message, HttpStatusCode statusCode, String responseBody) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public HttpStatusCode getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
