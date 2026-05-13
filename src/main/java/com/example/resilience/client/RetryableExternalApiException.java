package com.example.resilience.client;

import org.springframework.http.HttpStatusCode;

public class RetryableExternalApiException extends RuntimeException {

    private final HttpStatusCode statusCode;
    private final String responseBody;

    public RetryableExternalApiException(String message, HttpStatusCode statusCode, String responseBody) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public RetryableExternalApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = null;
        this.responseBody = null;
    }

    public HttpStatusCode getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
