# Spring Boot 4 + Java 21: Retry-Only RestClient with Resilience4j

A small, production-grade outbound HTTP layer for services that call several external APIs with
the same retry and logging rules.

It uses:

- Spring Boot 4, Java 21
- Spring `RestClient`
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
    -> factory.forDependency("step1-api")
ResilientApiClient
    -> ResilientRestClientExecutor   (retry + per-attempt logging)
    -> Spring RestClient             (JDK HttpClient + dependency timeouts)
```

| Type | Responsibility |
|------|----------------|
| `ExternalDependenciesProperties` | `@ConfigurationProperties(prefix = "external")`: base URL, timeouts, headers, retry params per dependency |
| `client.dto.*` records | Domain DTOs for JSON response bodies from each external API |
| `RestClientRegistryConfig` | Builds one `RestClient` per dependency into the `ExternalRestClients` bean |
| `ResilienceRetryConfig` | Builds one Resilience4j `RetryConfig` per dependency (instance name == dependency name) |
| `RetryEventLoggingConfig` / `RetryEventLogging` | Attaches DEBUG retry-lifecycle logging to every retry instance |
| `ResilientRestClientExecutor` | Attempt counting, per-attempt logging, body sanitization, status classification, retry integration |
| `ResilientApiClient` | Per-dependency facade: retry-decorated `get` / `exchangeIdempotent` |
| `ResilientApiClientFactory` | Resolves and caches one `ResilientApiClient` per dependency |
| `CorrelationIdContext` | MDC-backed correlation id, propagated across every retry attempt |
| `HttpResponseClassifier` / `ResponseBodySanitizer` | Retryable-status rules; secret masking + truncation for logs |

---

## Configuration

```yaml
external:
  dependencies:
    step1-api:
      base-url: https://api1.example.com
      connect-timeout: 2s
      read-timeout: 5s
      default-headers:
        Accept: application/json
      retry:
        max-attempts: 4          # original call + 3 retries
        initial-backoff: 500ms
        backoff-multiplier: 2.0
        jitter-factor: 0.0       # > 0 switches to exponential-random backoff
```

Adding a new dependency is a YAML-only change: add an `external.dependencies.<name>` entry.
No new beans and no new code are needed.

---

## Usage

```java
@Service
@RequiredArgsConstructor
public class OrderEnrichmentService {

    private final ResilientApiClientFactory factory;

    public String enrichOrder(String orderId) {
        CorrelationIdContext.getOrCreate(); // one id for the whole chain
        String order = factory.forDependency("step1-api").get("/orders/" + orderId);
        // each external call retries independently
    }
}
```

See [`OrderEnrichmentService`](src/main/java/com/example/resilience/client/OrderEnrichmentService.java)
for the full four-call sequential chain.

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

GET is the default-safe path. For mutations, use:

```java
client.exchangeIdempotent(method, uri, body, idempotencyKey)
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
