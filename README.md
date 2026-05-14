# Spring Boot 4 + Java 21: Resilient RestClient layer with Resilience4j

A graftable, config-driven outbound-HTTP layer for services that call several external APIs
with the same stack:

- Spring Boot 4, Java 21
- Spring `RestClient`
- `spring-cloud-starter-circuitbreaker-resilience4j`
- Resilience4j `RetryRegistry`
- DEBUG-only per-attempt response logging

Each external dependency is declared in `application.yml`; the code builds one `RestClient`,
one retry instance, and (optionally) wires one circuit breaker per dependency. Domain code
goes through a single factory and never touches retry names, circuit breaker factories, or
fallbacks directly.

---

## Architecture

```text
Domain service (e.g. OrderEnrichmentService)
    ↓ factory.forDependency("step1-api")
ResilientApiClient                      ← circuit breaker outside
    ↓
ResilientRestClientExecutor             ← retry inside, per-attempt logging
    ↓
Spring RestClient                       ← JDK HttpClient, per-dependency timeouts
```

| Type | Responsibility |
|------|----------------|
| `ExternalDependenciesProperties` | `@ConfigurationProperties(prefix = "external")` — base URL, timeouts, headers, retry params, circuit breaker name per dependency |
| `RestClientRegistryConfig` | Builds one `RestClient` per dependency into the `ExternalRestClients` bean |
| `ResilienceRetryConfig` | Builds one Resilience4j `RetryConfig` per dependency (instance name == dependency name) |
| `RetryEventLoggingConfig` / `RetryEventLogging` | Attaches DEBUG retry-lifecycle logging to every retry instance |
| `ResilientRestClientExecutor` | Attempt counting, per-attempt logging, body sanitization, status classification, retry integration |
| `ResilientApiClient` | Per-dependency facade: circuit-breaker-guarded `get` / `exchangeIdempotent` |
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
      circuit-breaker: step1ApiCircuitBreaker   # references resilience4j.circuitbreaker.instances

resilience4j:
  circuitbreaker:
    instances:
      step1ApiCircuitBreaker:
        sliding-window-type: count_based
        sliding-window-size: 20
        minimum-number-of-calls: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 3
        automatic-transition-from-open-to-half-open-enabled: true
```

Adding a new dependency is a YAML-only change: add an `external.dependencies.<name>` entry
(and a circuit breaker instance if you want one). No new beans, no new code.

---

## Usage

```java
@Service
@RequiredArgsConstructor
public class OrderEnrichmentService {

    private final ResilientApiClientFactory factory;

    public String enrichOrder(String orderId) {
        CorrelationIdContext.getOrCreate();                       // one id for the whole chain
        String order = factory.forDependency("step1-api").get("/orders/" + orderId);
        // ... each call retries independently and has its own circuit breaker
    }
}
```

See [`OrderEnrichmentService`](src/main/java/com/example/resilience/client/OrderEnrichmentService.java)
for the full four-call sequential chain, the intended adoption template.

---

## Retry semantics

`maxAttempts` includes the original call: `maxAttempts = 4` ⇒ original + 3 retries.

Retried:

```text
408 Request Timeout
429 Too Many Requests
5xx server errors
transport failures / timeouts (connect, read, mid-stream I/O)
```

Not retried:

```text
400 / 401 / 403 / 404 / 422 and other 4xx — most business errors
```

Classification lives in [`HttpResponseClassifier`](src/main/java/com/example/resilience/core/HttpResponseClassifier.java).

---

## Safe mutation retries

GET is the default-safe path. For mutations, `ResilientApiClient.exchangeIdempotent(method,
uri, body, idempotencyKey)`:

- The `idempotencyKey` is **mandatory** — a blank key throws `IllegalArgumentException`. There
  is no retrying-mutation method without a key, so unsafe retries are structurally impossible.
- The key is forwarded to the server as the `Idempotency-Key` header so the *server*
  deduplicates replays.
- For payments / order creation, pass a stable caller-generated key — not a per-attempt random
  value.

Plain non-idempotent mutations are intentionally not offered by the generic client; use the
raw `RestClient` for those.

---

## Correlation id

`CorrelationIdContext` stores the id in SLF4J `MDC`. `Retry.decorateSupplier` and the Spring
Cloud circuit breaker run synchronously on the calling thread, so the id survives every retry
attempt and is visible to retry event listeners — no need to thread it through method
signatures. Add `[%X{correlationId}]` to your logback pattern to surface it. An optional
`OncePerRequestFilter` that reads `X-Correlation-Id` (or generates one) per request is a
natural addition at the HTTP edge.

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

## Circuit breaker ordering

Circuit breaker outside, retry inside: one business call enters the circuit breaker; inside it
the executor may make several HTTP attempts. If all retries fail, the circuit breaker records
one failed business call. When the breaker is open, calls short-circuit to the fallback, which
throws `ExternalApiUnavailableException`.

---

## Building and testing

```bash
mvn test
```

Requires JDK 21 and Maven. Tests use JUnit 5, AssertJ, Mockito, and OkHttp `MockWebServer`
(a real local HTTP server, so real timeouts and transport failures are exercised). Integration
tests are named `*IT` and run alongside unit tests via the configured surefire includes.

Coverage:

- Unit: `ResponseBodySanitizerTest`, `HttpResponseClassifierTest`,
  `ExternalDependenciesPropertiesTest`, `ResilientApiClientFactoryTest`,
  `CorrelationIdContextTest`
- Integration: `ResilientRestClientExecutorRetryIT` (retry/backoff/classification/transport),
  `IdempotentMutationIT`, `PerAttemptLoggingIT`, `CorrelationIdPropagationIT`,
  `SequentialChainIT` (the four-call chain across four mock servers),
  `CircuitBreakerTransitionIT` (CLOSED → OPEN → HALF_OPEN → CLOSED),
  `ExternalDependenciesContextIT` (Spring wiring)

There is intentionally no `@SpringBootApplication` in `src/main` — this is shipped as graftable
components. A test-only `TestApplication` bootstraps the context for the wiring tests.

---

## Docs

- Resilience4j Retry: https://resilience4j.readme.io/docs/retry
- Spring RestClient: https://docs.spring.io/spring-framework/reference/integration/rest-clients.html
- Spring Cloud CircuitBreaker Resilience4j: https://docs.spring.io/spring-cloud-circuitbreaker/reference/spring-cloud-circuitbreaker-resilience4j.html
