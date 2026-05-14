package com.example.resilience.config;

import com.example.resilience.core.ExternalRestClients;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.HashMap;
import java.util.Map;

/**
 * Builds one {@link RestClient} per configured external dependency, wrapped in a single
 * {@link ExternalRestClients} bean keyed by dependency name. A keyed wrapper (rather than one
 * named bean per dependency) keeps the wiring zero-maintenance as dependencies are added in
 * {@code application.yml}; {@code ResilientApiClientFactory} resolves entries by name.
 */
@Configuration
@EnableConfigurationProperties(ExternalDependenciesProperties.class)
public class RestClientRegistryConfig {

    @Bean
    public ExternalRestClients externalRestClients(
            RestClient.Builder builder,
            ExternalDependenciesProperties properties
    ) {
        Map<String, RestClient> clients = new HashMap<>();

        properties.getDependencies().forEach((name, dependency) -> {
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(dependency.getConnectTimeout())
                    .build();

            JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
            requestFactory.setReadTimeout(dependency.getReadTimeout());

            RestClient.Builder dependencyBuilder = builder.clone()
                    .baseUrl(dependency.getBaseUrl())
                    .requestFactory(requestFactory);

            dependency.getDefaultHeaders().forEach(dependencyBuilder::defaultHeader);

            clients.put(name, dependencyBuilder.build());
        });

        return new ExternalRestClients(clients);
    }
}
