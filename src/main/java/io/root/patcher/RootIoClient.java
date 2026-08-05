package io.root.patcher;

import org.gradle.api.GradleException;

import groovy.json.JsonOutput;
import groovy.json.JsonSlurper;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntToLongFunction;

/** HTTP client for querying the Root.io {@code /v3/analyze/v2/maven} API, with exponential backoff retries. */
public class RootIoClient {
    private static final Logger logger = Logging.getLogger(RootIoClient.class);

    private final HttpClient httpClient;
    private final int maxRetries;
    private final IntToLongFunction retryDelayMs;

    /**
     * Creates a client with the given retry settings and exponential backoff.
     *
     * @param maxRetries   maximum number of retry attempts after the initial request
     * @param baseDelayMs  base delay in milliseconds; doubles on each subsequent attempt
     */
    public RootIoClient(int maxRetries, long baseDelayMs) {
        this(
            // Shared across all query() calls within a build — reuses TLS connections
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build(),
            maxRetries,
            attempt -> baseDelayMs * (1L << attempt)
        );
    }

    /** Package-private constructor for tests — allows injecting a fake HTTP client and delay function. */
    RootIoClient(HttpClient httpClient, int maxRetries, IntToLongFunction retryDelayMs) {
        this.httpClient = httpClient;
        this.maxRetries = maxRetries;
        this.retryDelayMs = retryDelayMs;
    }

    private static final String ENDPOINT_ANALYZE_MAVEN = "/v3/analyze/v2/maven";

    private static final String REQUEST_PACKAGES = "packages";
    private static final String REQUEST_PACKAGE_NAME = "name";
    private static final String REQUEST_PACKAGE_VERSION = "version";
    private static final String REQUEST_IGNORE = "ignore";

    private static final String RESPONSE_PATCHES = "patches";
    private static final String RESPONSE_PATCH_ALIAS = "patch_alias";
    private static final String RESPONSE_PATCH = "patch";

    /**
     * Query the Root.io API for a patch for the given dependency, with exponential backoff retries.
     * Retries on network errors and 5xx responses; fails immediately on 4xx.
     *
     * @param coords  Maven GAV string — "group:artifact:version"
     * @param apiUrl  Root.io API base URL (e.g. "<a href="https://api.root.io">...</a>")
     * @param apiKey  Root.io API key (used as HTTP basic auth username)
     * @param useAlias  true to read the aliased coordinate ("io.root.group:artifact:version"),
     *                  false to read the upstream-group coordinate ("group:artifact:version-root.io.N")
     * @return patched GAV string, or null if no patch
     * @throws GradleException after all retries are exhausted, or immediately on 4xx
     */
    public String query(String coords, List<String> ignoreEntries, String apiUrl, String apiKey, boolean useAlias) {
        String[] parts = coords.split(":", 3);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            logger.warn("Skipping malformed coords (expected group:artifact:version): {}", coords);
            return null;
        }

        logger.debug("Querying Root.io API for {} at {}...", coords, apiUrl);
        HttpRequest request = prepareHttpRequest(coords, ignoreEntries, apiUrl, apiKey);
        Exception lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            backoffDelay(coords, attempt);

            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                if (status == 200) {
                    logger.debug("Root.io API response for {}: {}", coords, response.body());
                    return extractPatchedCoords(response.body(), useAlias);
                }
                if (status >= 500) {
                    lastException = new GradleException("Root.io API returned HTTP " + status + " for " + coords);
                    continue; // retry on 5xx
                }
                // 4xx — client error, no point retrying
                throw new GradleException("Root.io API returned HTTP " + status + " for " + coords);
            } catch (GradleException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GradleException("Root.io API request interrupted for " + coords, e);
            } catch (IOException e) {
                lastException = e;
                // retry
            }
        }

        // a valid request would have returned early, if we got here we should throw
        if (lastException == null) {
            throw new GradleException(
                    "Root.io API request failed for " + coords + " after " + maxRetries + " retries");
        }

        throw new GradleException(
            "Root.io API request failed for " + coords + " after " + maxRetries + " retries: " + lastException.getMessage(),
            lastException);
    }

    private void backoffDelay(String coords, int attempt) {
        if (attempt == 0) {
            return;
        }

        logger.warn("Retrying Root.io API request for {} (attempt {}/{})...", coords, attempt, maxRetries);
        try {
            Thread.sleep(retryDelayMs.applyAsLong(attempt - 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GradleException("Root.io API request interrupted for " + coords, e);
        }
    }

    private static HttpRequest prepareHttpRequest(String coords, List<String> ignoreEntries, String apiUrl, String apiKey) {
        // Split "group:artifact:version" — last colon separates version
        int lastColon = coords.lastIndexOf(':');
        String groupArtifact = coords.substring(0, lastColon);
        String version = coords.substring(lastColon + 1);

        Map<String, Object> bodyMap = new LinkedHashMap<>();
        bodyMap.put(REQUEST_PACKAGES, List.of(Map.of(
                REQUEST_PACKAGE_NAME, groupArtifact,
                REQUEST_PACKAGE_VERSION, version)));
        if (ignoreEntries != null && !ignoreEntries.isEmpty()) {
            List<Map<String, String>> ignoreList = new ArrayList<>();
            for (String entry : ignoreEntries) {
                int at = entry.lastIndexOf('@');
                if (at > 0 && at < entry.length() - 1) {
                    ignoreList.add(Map.of(
                            REQUEST_PACKAGE_NAME, entry.substring(0, at),
                            REQUEST_PACKAGE_VERSION, entry.substring(at + 1)));
                }
            }
            if (!ignoreList.isEmpty()) {
                bodyMap.put(REQUEST_IGNORE, ignoreList);
            }
        }
        String requestBody = JsonOutput.toJson(bodyMap);
        String endpoint = apiUrl.replaceAll("/$", "") + ENDPOINT_ANALYZE_MAVEN;

        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
        if (apiKey != null && !apiKey.isEmpty()) {
            builder.header("Authorization", basicAuthHeader(apiKey, ""));
        }
        return builder.build();
    }

    private static String basicAuthHeader(String username, String password) {
        String rawCredentials = username + ":" + password;
        String credentials = Base64.getEncoder()
            .encodeToString((rawCredentials).getBytes(StandardCharsets.UTF_8));
        return "Basic " + credentials;
    }

    @SuppressWarnings("unchecked")
    private static String extractPatchedCoords(String json, boolean useAlias) {
        String field = useAlias ? RESPONSE_PATCH_ALIAS : RESPONSE_PATCH;
        try {
            Map<String, Object> root = (Map<String, Object>) new JsonSlurper().parseText(json);
            List<Map<String, Object>> patches = (List<Map<String, Object>>) root.get(RESPONSE_PATCHES);
            if (patches == null || patches.isEmpty()) {
                return null;
            }

            Map<String, Object> patch = (Map<String, Object>) patches.get(0).get(field);
            if (patch == null) {
                return null;
            }

            String name = (String) patch.get(REQUEST_PACKAGE_NAME);
            if (name == null || name.isEmpty()) {
                logger.warn("Root.io API returned {} without name: {}", field, patch);
                return null;
            }

            String version = (String) patch.get(REQUEST_PACKAGE_VERSION);
            if (version == null || version.isEmpty()) {
                logger.warn("Root.io API returned {} without version: {}", field, patch);
                return null;
            }

            return name + ":" + version;
        } catch (ClassCastException e) {
            throw new GradleException("Root.io API returned unexpected JSON structure: " + e.getMessage(), e);
        }
    }
}
