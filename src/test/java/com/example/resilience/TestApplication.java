package com.example.resilience;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Test-only entry point. Production is shipped as graftable components, so it intentionally has
 * no {@code @SpringBootApplication}; this class exists solely so {@code @SpringBootTest} can
 * bootstrap a context for the wiring tests.
 */
@SpringBootApplication
public class TestApplication {
}
