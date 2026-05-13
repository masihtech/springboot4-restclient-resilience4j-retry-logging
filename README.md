# Spring Boot 4 + Java 21: RestClient Retry Logging with Resilience4j

Reference implementation for using `io.github.resilience4j.retry` effectively with:

- Spring Boot 4
- Java 21
- Spring `RestClient`
- `spring-cloud-starter-circuitbreaker-resilience4j`
- Resilience4j `RetryRegistry`
- DEBUG-only per-attempt response logging

The target use case is simple:

> For every outbound HTTP attempt, log the attempt number, response status, response body, timing, and retry scheduling details — without leaking secrets and without retrying unsafe failures.

---

## Why programmatic retry instead of only `@Retry`?

`@Retry` is fine for simple service retries, but it is weak for this requirement:

- You cannot cleanly access the current attempt number inside the annotated method.
- `RestClient.retrieve()` throws on `4xx`/`5xx` by default.
- Retry events tell you retry lifecycle details, but they do not expose the HTTP response body for every try.

This sample uses:

```text
RestClient.exchange()
+ Retry.decorateSupplier(...)
+ explicit retryable/non-retryable exceptions
+ AtomicInteger attempt counter
+ DEBUG guarded response logging
```

`RestClient.exchange()` is important because it gives access to the raw status, headers, and body before you decide whether to retry.

---

## Retry semantics

Resilience4j `maxAttempts` includes the original call.

```text
maxAttempts = 3

attempt 1 = original call
attempt 2 = retry #1
attempt 3 = retry #2
```

---

## Dependencies

If you already have this dependency:

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-circuitbreaker-resilience4j</artifactId>
</dependency>
```

I still recommend adding the retry module explicitly if your code directly uses `Retry`, `RetryRegistry`, or `RetryConfig`:

```xml
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-retry</artifactId>
</dependency>
```

See [`pom.xml`](pom.xml) for a minimal Maven example.

---

## Recommended architecture

```text
Business Service
    ↓
External API Client
    ↓
ResilientRestClientExecutor
    ↓
Spring RestClient
```

The executor owns:

- attempt counting
- response logging
- response body sanitization/truncation
- retryable status classification
- exception classification
- integration with Resilience4j Retry

Your business client remains small.

---

## Core implementation

See:

- [`ResilienceRetryConfig.java`](src/main/java/com/example/resilience/config/ResilienceRetryConfig.java)
- [`ResilientRestClientExecutor.java`](src/main/java/com/example/resilience/client/ResilientRestClientExecutor.java)
- [`RetryEventLoggingConfig.java`](src/main/java/com/example/resilience/config/RetryEventLoggingConfig.java)
- [`CustomerApiClient.java`](src/main/java/com/example/resilience/client/CustomerApiClient.java)

---

## What gets retried?

This sample retries:

```text
408 Request Timeout
429 Too Many Requests
5xx server errors
transport failures / timeouts
```

This sample does not retry:

```text
400 Bad Request
401 Unauthorized
403 Forbidden
404 Not Found
422 Validation Error
most business errors
```

For `POST`, `PUT`, payments, order creation, or any operation that changes state: only retry if you have an idempotency key.

---

## Example logs

Attempt 1 fails with `503`:

```text
external_call_attempt_started dependency=customer-api correlationId=abc-123 retryName=externalApiRetry attempt=1 method=GET uri=/customers/123
external_call_attempt_response dependency=customer-api correlationId=abc-123 retryName=externalApiRetry attempt=1 method=GET uri=https://api.example.com/customers/123 status=503 durationMs=184 body={"error":"temporarily unavailable"}
retry_scheduled retryName=externalApiRetry failedAttempt=1 nextAttempt=2 waitMs=500 errorClass=RetryableExternalApiException errorMessage=Retryable HTTP response from customer-api: 503
```

Attempt 2 succeeds:

```text
external_call_attempt_started dependency=customer-api correlationId=abc-123 retryName=externalApiRetry attempt=2 method=GET uri=/customers/123
external_call_attempt_response dependency=customer-api correlationId=abc-123 retryName=externalApiRetry attempt=2 method=GET uri=https://api.example.com/customers/123 status=200 durationMs=91 body={"id":"123","name":"John"}
retry_success retryName=externalApiRetry retryAttempts=1
```

---

## Configuration

```yaml
spring:
  threads:
    virtual:
      enabled: true
  cloud:
    circuitbreaker:
      resilience4j:
        enabled: true

resilience4j:
  circuitbreaker:
    instances:
      customerApiCircuitBreaker:
        sliding-window-type: count_based
        sliding-window-size: 20
        minimum-number-of-calls: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 3
        automatic-transition-from-open-to-half-open-enabled: true

logging:
  level:
    com.example.resilience: DEBUG
```

---

## Circuit breaker ordering

Recommended order for synchronous external calls:

```text
CircuitBreaker outside
    Retry inside
        RestClient call
```

That means one business call enters the circuit breaker. Inside it, retry may make 2–3 HTTP attempts. If all retry attempts fail, the circuit breaker records one failed business call.

See [`CustomerApiClient.java`](src/main/java/com/example/resilience/client/CustomerApiClient.java).

---

## Timeout guidance

Retries without timeouts are dangerous.

Starting point:

```text
connect timeout: 1–2 seconds
read timeout: 3–5 seconds
max attempts: 2–3
backoff: 300–500ms exponential
```

See [`RestClientConfig.java`](src/main/java/com/example/resilience/config/RestClientConfig.java).

---

## Docs

- Resilience4j Retry docs: https://resilience4j.readme.io/docs/retry
- Resilience4j Retry Javadocs: https://javadoc.io/doc/io.github.resilience4j/resilience4j-retry/latest/io/github/resilience4j/retry/Retry.html
- Spring RestClient docs: https://docs.spring.io/spring-framework/reference/integration/rest-clients.html
- Spring Cloud CircuitBreaker Resilience4j docs: https://docs.spring.io/spring-cloud-circuitbreaker/reference/spring-cloud-circuitbreaker-resilience4j.html
