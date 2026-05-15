# Spring Boot 4 + Java 21: Retry-Only RestClient with Resilience4j

A small, production-grade outbound HTTP layer for services that call several external APIs with
the same retry and logging rules.

It uses:

- Spring Boot 4, Java 21
- Spring `RestClient`
- Apache HttpClient 5 connection pooling
- Resilience4j `RetryRegistry`
- Java records for external API response DTOs
- Lombok for constructor/getter/setter boilerplate
- DEBUG-only per-attempt response logging

This project intentionally does **not** include circuit breakers. Each dependency gets one
`RestClient` and one retry instance. Domain code goes through one factory and does not deal with
retry names or low-level HTTP plumbing.

---

## Architecture

```text
Domain service (for example OrderEnrichmentService)
    -> API-specific service interface (for example OrderExternalApiService)
        -> factory.forDependency("step1-api")
ResilientApiClient
    -> ResilientRestClientExecutor   (retry + per-attempt logging)
    -> Spring RestClient             (Apache HttpClient pool + dependency timeouts)
```

| Type | Responsibility |
|------|----------------|
| `ExternalDependenciesProperties` | `@ConfigurationProperties(prefix = "external")`: base URL, timeouts, headers, retry params per dependency |
| `client.dto.*` records | Domain DTOs for JSON response bodies from each external API |
| `*ExternalApiService` interfaces | Per-API domain boundaries: URI construction, DTO mapping, and business rules for that external contract |
| `RestClientRegistryConfig` | Builds one pooled Apache-backed `RestClient` per dependency into the `ExternalRestClients` bean |
| `ResilienceRetryConfig` | Builds one Resilience4j `RetryConfig` per dependency (instance name == dependency name) |
| `RetryEventLoggingConfig` / `RetryEventLogging` | Attaches DEBUG retry-lifecycle logging to every retry instance |
| `ResilientRestClientExecutor` | Attempt counting, per-attempt logging, body sanitization, status classification, retry integration |
| `ApiRequest` | Per-call URI template variables, query params, and request headers |
| `ResilientApiClient` | Per-dependency facade: retry-decorated `get` / `exchangeIdempotent` for string URIs or `ApiRequest` |
| `ResilientApiClientFactory` | Resolves and caches one `ResilientApiClient` per dependency |
| `CorrelationIdContext` | MDC-backed correlation id, propagated across every retry attempt |
| `HttpResponseClassifier` / `ResponseBodySanitizer` | Retryable-status rules; secret masking + truncation for logs |

---

## Configuration

```properties
external.dependencies.step1-api.base-url=https://api1.example.com
external.dependencies.step1-api.connect-timeout=2s
external.dependencies.step1-api.read-timeout=5s
external.dependencies.step1-api.connection-request-timeout=2s
external.dependencies.step1-api.max-connections=20
external.dependencies.step1-api.ssl-bundle=partner-api-tls
external.dependencies.step1-api.default-headers.Accept=application/json
external.dependencies.step1-api.retry.max-attempts=4
external.dependencies.step1-api.retry.initial-backoff=500ms
external.dependencies.step1-api.retry.backoff-multiplier=2.0
external.dependencies.step1-api.retry.jitter-factor=0.0

spring.ssl.bundle.jks.partner-api-tls.truststore.location=classpath:tls/partner-truststore.p12
spring.ssl.bundle.jks.partner-api-tls.truststore.password=${PARTNER_TRUSTSTORE_PASSWORD}
spring.ssl.bundle.jks.partner-api-tls.keystore.location=classpath:tls/client-cert.p12
spring.ssl.bundle.jks.partner-api-tls.keystore.password=${PARTNER_KEYSTORE_PASSWORD}
```

Adding HTTP wiring for a new dependency is a properties-only change: add an
`external.dependencies.<name>` entry. Domain-specific DTO mapping and business rules still belong
behind a small API-specific service interface.

Apache HttpClient automatic retries are disabled; Resilience4j remains the only retry owner so
attempt counts and retry logs stay accurate.
Omit the `keystore` block when the API only needs a custom trust store and not mutual TLS.

---

## Usage

```java
@Service
@RequiredArgsConstructor
public class OrderEnrichmentService {

    private final OrderExternalApiService orderService;
    private final CustomerExternalApiService customerService;
    private final PricingExternalApiService pricingService;
    private final InventoryExternalApiService inventoryService;

    public InventoryDto enrichOrder(String orderId) {
        CorrelationIdContext.getOrCreate(); // one id for the whole chain
        OrderDto order = orderService.getOrder(orderId);
        CustomerDto customer = customerService.getCustomer(orderId, order.customerId());
        PricingDto pricing = pricingService.getPricing(customer.pricingTier());
        return inventoryService.getInventory(pricing.pricingTier(), pricing.skuId());
    }
}
```

See [`OrderEnrichmentService`](src/main/java/com/example/resilience/client/OrderEnrichmentService.java)
for the full four-call sequential chain.

Each API-specific service owns its own request shape. Simple calls can still use
`get("/orders/123")`; use `ApiRequest` when an API needs URI template variables, query params,
or request-specific headers:

```java
CustomerDto customer = factory.forDependency("step2-api")
        .get(ApiRequest.builder("/orders/{orderId}/customers/{customerId}")
                .uriVariable("orderId", orderId)
                .uriVariable("customerId", customerId)
                .queryParam("fields", "pricingTier")
                .header("X-Customer-Use-Case", "pricing")
                .build(), CustomerDto.class);
```

---

## Retry Semantics

`maxAttempts` includes the original call: `maxAttempts = 4` means original + 3 retries.

Retried:

```text
408 Request Timeout
429 Too Many Requests
5xx server errors
transport failures / timeouts (connect, read, mid-stream I/O)
```

Not retried:

```text
400 / 401 / 403 / 404 / 422 and other 4xx
```

Classification lives in [`HttpResponseClassifier`](src/main/java/com/example/resilience/core/HttpResponseClassifier.java).

---

## Safe Mutation Retries

GET is the default-safe path. For mutations, use either a URI string or `ApiRequest`:

```java
client.exchangeIdempotent(method, uri, body, idempotencyKey)
client.exchangeIdempotent(method, request, body, idempotencyKey)
```

- The `idempotencyKey` is mandatory. A blank key throws `IllegalArgumentException`.
- The key is forwarded as the `Idempotency-Key` header so the server can deduplicate replays.
- For payments or order creation, pass a stable caller-generated key, not a per-attempt random value.

Plain non-idempotent mutations are intentionally not offered by the generic client. Use a raw
`RestClient` for those so unsafe retries stay explicit.

---

## Correlation ID

`CorrelationIdContext` stores the id in SLF4J `MDC`. `Retry.decorateSupplier` runs
synchronously on the calling thread, so the id survives every retry attempt and is visible to
retry event listeners. Add `[%X{correlationId}]` to your logback pattern to surface it.

At an HTTP edge, prefer a request filter that reads `X-Correlation-Id` or generates one, then
clears it in `finally`.

---

## Logging

All logging is DEBUG and guarded by `log.isDebugEnabled()`.

```text
external_call_attempt_started   dependency=step1-api correlationId=abc attempt=1 method=GET uri=/orders/1
external_call_attempt_response  dependency=step1-api correlationId=abc attempt=1 status=503 durationMs=184 body={"error":"unavailable"}
retry_scheduled                 retryName=step1-api failedAttempt=1 nextAttempt=2 waitMs=500 ...
external_call_attempt_started   dependency=step1-api correlationId=abc attempt=2 method=GET uri=/orders/1
external_call_attempt_response  dependency=step1-api correlationId=abc attempt=2 status=200 durationMs=91 body={"id":"1"}
retry_success                   retryName=step1-api retryAttempts=1
```

Logged response bodies are passed through [`ResponseBodySanitizer`](src/main/java/com/example/resilience/core/ResponseBodySanitizer.java):
`access_token`, `refresh_token`, `password`, `secret`, `apiKey` are masked, and bodies over
2000 chars are truncated.

---

## Building and Testing

```bash
mvn test
```

Requires JDK 21 and Maven. Tests use JUnit 5, AssertJ, Mockito, and OkHttp `MockWebServer`.
Integration tests are named `*IT` and run alongside unit tests via the configured surefire
includes.

Coverage:

- Unit: `ResponseBodySanitizerTest`, `HttpResponseClassifierTest`,
  `ExternalDependenciesPropertiesTest`, `ResilientApiClientFactoryTest`,
  `CorrelationIdContextTest`
- Integration: `ResilientRestClientExecutorRetryIT` (retry/backoff/classification/transport),
  `IdempotentMutationIT`, `PerAttemptLoggingIT`, `CorrelationIdPropagationIT`,
  `SequentialChainIT` (the four-call chain across four mock servers),
  `ExternalDependenciesContextIT` (Spring wiring)

There is intentionally no `@SpringBootApplication` in `src/main`; this is shipped as graftable
components. A test-only `TestApplication` bootstraps the context for wiring tests.

---

## Docs

- Resilience4j Retry: https://resilience4j.readme.io/docs/retry
- Spring RestClient: https://docs.spring.io/spring-framework/reference/integration/rest-clients.html
