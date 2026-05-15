package com.example.resilience.config;

import com.example.resilience.core.ExternalRestClients;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
            ExternalDependenciesProperties properties,
            ObjectProvider<SslBundles> sslBundlesProvider
    ) {
        Map<String, RestClient> clients = new HashMap<>();
        List<Closeable> closeables = new ArrayList<>();

        properties.getDependencies().forEach((name, dependency) -> {
            PoolingHttpClientConnectionManagerBuilder connectionManagerBuilder =
                    PoolingHttpClientConnectionManagerBuilder.create()
                            .setMaxConnTotal(dependency.getMaxConnections())
                            .setMaxConnPerRoute(dependency.getMaxConnections())
                            .setDefaultConnectionConfig(ConnectionConfig.custom()
                                    .setConnectTimeout(Timeout.of(dependency.getConnectTimeout()))
                                    .build());

            if (dependency.getSslBundle() != null && !dependency.getSslBundle().isBlank()) {
                SslBundles sslBundles = sslBundlesProvider.getIfAvailable();
                if (sslBundles == null) {
                    throw new IllegalStateException("No Spring SSL bundles are available for " + name);
                }
                SslBundle sslBundle = sslBundles.getBundle(dependency.getSslBundle());
                connectionManagerBuilder.setSSLSocketFactory(SSLConnectionSocketFactoryBuilder.create()
                        .setSslContext(sslBundle.createSslContext())
                        .build());
            }

            PoolingHttpClientConnectionManager connectionManager = connectionManagerBuilder.build();

            CloseableHttpClient httpClient = HttpClients.custom()
                    .setConnectionManager(connectionManager)
                    .disableAutomaticRetries()
                    .evictExpiredConnections()
                    .evictIdleConnections(TimeValue.ofSeconds(30))
                    .build();
            closeables.add(httpClient);

            HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient);
            requestFactory.setConnectionRequestTimeout(dependency.getConnectionRequestTimeout());
            requestFactory.setReadTimeout(dependency.getReadTimeout());

            RestClient.Builder dependencyBuilder = builder.clone()
                    .baseUrl(dependency.getBaseUrl())
                    .requestFactory(requestFactory);

            dependency.getDefaultHeaders().forEach(dependencyBuilder::defaultHeader);

            clients.put(name, dependencyBuilder.build());
        });

        return new ExternalRestClients(clients, closeables);
    }
}
