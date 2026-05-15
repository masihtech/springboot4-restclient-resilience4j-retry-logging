package com.example.resilience.core;

import org.springframework.http.HttpHeaders;
import org.springframework.util.CollectionUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.lang.reflect.Array;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable request metadata for one outbound API call. It lets callers keep URI templates,
 * path variables, query parameters, and request-specific headers separate until the
 * {@link RestClient} request is created.
 */
public final class ApiRequest {

    private static final Pattern URI_SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:.*");

    private final String uriTemplate;
    private final Map<String, Object> uriVariables;
    private final MultiValueMap<String, Object> queryParams;
    private final HttpHeaders headers;

    private ApiRequest(
            String uriTemplate,
            Map<String, Object> uriVariables,
            MultiValueMap<String, Object> queryParams,
            HttpHeaders headers
    ) {
        this.uriTemplate = requireText(uriTemplate, "uriTemplate");
        this.uriVariables = Collections.unmodifiableMap(new LinkedHashMap<>(uriVariables));
        this.queryParams = copyQueryParams(queryParams);

        HttpHeaders headersCopy = new HttpHeaders();
        headersCopy.addAll(headers);
        this.headers = HttpHeaders.readOnlyHttpHeaders(headersCopy);
    }

    public static ApiRequest uri(String uri) {
        return builder(uri).build();
    }

    public static Builder builder(String uriTemplate) {
        return new Builder(uriTemplate);
    }

    public String uriTemplate() {
        return uriTemplate;
    }

    public Map<String, Object> uriVariables() {
        return uriVariables;
    }

    public MultiValueMap<String, Object> queryParams() {
        return CollectionUtils.unmodifiableMultiValueMap(queryParams);
    }

    public HttpHeaders headers() {
        return headers;
    }

    URI toUri(UriBuilder baseBuilder) {
        Objects.requireNonNull(baseBuilder, "baseBuilder must not be null");
        if (hasUriScheme(uriTemplate)) {
            return buildAbsoluteUri();
        }

        UriBuilder builder = applyRelativeTemplate(baseBuilder);
        addQueryParams(builder);
        return build(builder);
    }

    String logUri() {
        if (queryParams.isEmpty()) {
            return uriTemplate;
        }

        StringBuilder builder = new StringBuilder(uriTemplate);
        appendQuerySeparator(builder);

        boolean first = true;
        for (Map.Entry<String, List<Object>> entry : queryParams.entrySet()) {
            for (Object value : entry.getValue()) {
                if (!first) {
                    builder.append('&');
                }
                builder.append(entry.getKey()).append('=').append(value);
                first = false;
            }
        }
        return builder.toString();
    }

    private URI buildAbsoluteUri() {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(uriTemplate);
        addQueryParams(builder);
        if (uriVariables.isEmpty()) {
            return builder.build().encode().toUri();
        }
        return builder.buildAndExpand(uriVariables).encode().toUri();
    }

    private UriBuilder applyRelativeTemplate(UriBuilder builder) {
        int queryStart = uriTemplate.indexOf('?');
        if (queryStart < 0) {
            return builder.path(uriTemplate);
        }

        String path = uriTemplate.substring(0, queryStart);
        String query = uriTemplate.substring(queryStart + 1);
        builder.path(path);
        if (!query.isBlank()) {
            builder.query(query);
        }
        return builder;
    }

    private void addQueryParams(UriBuilder builder) {
        queryParams.forEach((name, values) -> values.forEach(value -> builder.queryParam(name, value)));
    }

    private URI build(UriBuilder builder) {
        if (uriVariables.isEmpty()) {
            return builder.build();
        }
        return builder.build(uriVariables);
    }

    private static void appendQuerySeparator(StringBuilder builder) {
        String current = builder.toString();
        if (current.endsWith("?") || current.endsWith("&")) {
            return;
        }
        builder.append(current.contains("?") ? '&' : '?');
    }

    private static MultiValueMap<String, Object> copyQueryParams(MultiValueMap<String, Object> source) {
        LinkedMultiValueMap<String, Object> copy = new LinkedMultiValueMap<>();
        source.forEach((name, values) -> copy.put(name, List.copyOf(values)));
        return copy;
    }

    private static boolean hasUriScheme(String uriTemplate) {
        return URI_SCHEME.matcher(uriTemplate).matches();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static Object requireValue(Object value, String field) {
        return Objects.requireNonNull(value, field + " must not be null");
    }

    public static final class Builder {

        private final String uriTemplate;
        private final Map<String, Object> uriVariables = new LinkedHashMap<>();
        private final LinkedMultiValueMap<String, Object> queryParams = new LinkedMultiValueMap<>();
        private final HttpHeaders headers = new HttpHeaders();

        private Builder(String uriTemplate) {
            this.uriTemplate = requireText(uriTemplate, "uriTemplate");
        }

        public Builder uriVariable(String name, Object value) {
            uriVariables.put(requireText(name, "uri variable name"), requireValue(value, "uri variable value"));
            return this;
        }

        public Builder uriVariables(Map<String, ?> variables) {
            Objects.requireNonNull(variables, "variables must not be null")
                    .forEach(this::uriVariable);
            return this;
        }

        public Builder queryParam(String name, Object... values) {
            String queryName = requireText(name, "query parameter name");
            if (values == null || values.length == 0) {
                throw new IllegalArgumentException("query parameter values must not be empty");
            }
            Arrays.stream(values)
                    .map(value -> requireValue(value, "query parameter value"))
                    .forEach(value -> queryParams.add(queryName, value));
            return this;
        }

        public Builder queryParam(String name, Iterable<?> values) {
            String queryName = requireText(name, "query parameter name");
            Objects.requireNonNull(values, "query parameter values must not be null")
                    .forEach(value -> queryParams.add(queryName, requireValue(value, "query parameter value")));
            return this;
        }

        public Builder queryParams(Map<String, ?> params) {
            Objects.requireNonNull(params, "params must not be null")
                    .forEach(this::addQueryParamValue);
            return this;
        }

        public Builder header(String name, String... values) {
            String headerName = requireText(name, "header name");
            if (values == null || values.length == 0) {
                throw new IllegalArgumentException("header values must not be empty");
            }
            Arrays.stream(values)
                    .map(value -> (String) requireValue(value, "header value"))
                    .forEach(value -> headers.add(headerName, value));
            return this;
        }

        public Builder headers(Map<String, String> headers) {
            Objects.requireNonNull(headers, "headers must not be null")
                    .forEach(this::header);
            return this;
        }

        public Builder headers(HttpHeaders headers) {
            Objects.requireNonNull(headers, "headers must not be null")
                    .forEach((name, values) -> header(name, values.toArray(String[]::new)));
            return this;
        }

        public ApiRequest build() {
            return new ApiRequest(uriTemplate, uriVariables, queryParams, headers);
        }

        private void addQueryParamValue(String name, Object value) {
            if (value instanceof Iterable<?> values && !(value instanceof CharSequence)) {
                queryParam(name, values);
                return;
            }

            if (value != null && value.getClass().isArray()) {
                int length = Array.getLength(value);
                Object[] values = new Object[length];
                for (int index = 0; index < length; index++) {
                    values[index] = Array.get(value, index);
                }
                queryParam(name, values);
                return;
            }

            queryParam(name, value);
        }
    }
}
