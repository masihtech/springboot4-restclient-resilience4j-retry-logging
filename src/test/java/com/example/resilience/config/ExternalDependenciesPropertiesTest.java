package com.example.resilience.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalDependenciesPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void bindsDependenciesAndAppliesDefaults() {
        runner.withPropertyValues(
                "external.dependencies.step1-api.base-url=https://api1.example.com",
                "external.dependencies.step1-api.read-timeout=7s"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            ExternalDependenciesProperties props = context.getBean(ExternalDependenciesProperties.class);
            ExternalDependenciesProperties.Dependency dep = props.getRequired("step1-api");

            assertThat(dep.getBaseUrl()).isEqualTo("https://api1.example.com");
            assertThat(dep.getReadTimeout()).isEqualTo(Duration.ofSeconds(7));
            assertThat(dep.getConnectTimeout()).isEqualTo(Duration.ofSeconds(2)); // default
            assertThat(dep.getRetry().getMaxAttempts()).isEqualTo(4);             // default
            assertThat(dep.getRetry().getBackoffMultiplier()).isEqualTo(2.0);     // default
        });
    }

    @Test
    void rejectsBlankBaseUrl() {
        runner.withPropertyValues(
                "external.dependencies.bad.base-url="
        ).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsMaxAttemptsBelowOne() {
        runner.withPropertyValues(
                "external.dependencies.bad.base-url=https://x",
                "external.dependencies.bad.retry.max-attempts=0"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void getRequiredThrowsForUnknownDependency() {
        runner.withPropertyValues(
                "external.dependencies.known.base-url=https://x"
        ).run(context -> {
            ExternalDependenciesProperties props = context.getBean(ExternalDependenciesProperties.class);
            assertThat(props.getDependencies()).containsKey("known");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> props.getRequired("missing"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("missing");
        });
    }

    @EnableConfigurationProperties(ExternalDependenciesProperties.class)
    static class TestConfig {
    }
}
