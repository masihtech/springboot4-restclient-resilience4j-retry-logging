package com.example.resilience.client;

public class ExternalApiUnavailableException extends RuntimeException {

    public ExternalApiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
