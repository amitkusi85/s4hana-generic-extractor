package com.example.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

public class ExtractorConfig {

    private final String baseUrl;
    private final String user;
    private final String password;
    private final String client;
    private final String preferHeader;
    private final String servicePath;
    private final String entitySet;
    private final String extractionMode;
    private final int parallelCalls;
    private final int pageSizeOverride; // 0 means “use Prefer header”
    private final List<String> servicePaths;

    public ExtractorConfig(String baseUrl, String user, String password, String client,
                           String preferHeader, String servicePath, String entitySet,
                           String extractionMode, int parallelCalls) {
        this(baseUrl, user, password, client, preferHeader, servicePath, entitySet,
                extractionMode, parallelCalls, 0, null);
    }

    public ExtractorConfig(String baseUrl, String user, String password, String client,
                           String preferHeader, String servicePath, String entitySet,
                           String extractionMode, int parallelCalls, int pageSizeOverride) {
        this(baseUrl, user, password, client, preferHeader, servicePath, entitySet,
                extractionMode, parallelCalls, pageSizeOverride, null);
    }

    public ExtractorConfig(String baseUrl, String user, String password, String client,
                           String preferHeader, String servicePath, String entitySet,
                           String extractionMode, int parallelCalls, int pageSizeOverride,
                           List<String> servicePaths) {
        this.baseUrl = baseUrl;
        this.user = user;
        this.password = password;
        this.client = client;
        this.preferHeader = preferHeader;
        this.servicePath = servicePath;
        this.entitySet = entitySet;
        this.extractionMode = extractionMode;
        this.parallelCalls = parallelCalls;
        this.pageSizeOverride = pageSizeOverride;
        // Build the public list, ensuring the primary servicePath is present and order-preserving.
        Set<String> all = new LinkedHashSet<>();
        if (servicePath != null && !servicePath.isBlank()) all.add(servicePath);
        if (servicePaths != null) {
            for (String p : servicePaths) {
                if (p != null && !p.isBlank()) all.add(p);
            }
        }
        this.servicePaths = Collections.unmodifiableList(new ArrayList<>(all));
    }

    public static ExtractorConfig fromProperties() {
        Properties props = new Properties();
        try (InputStream is = ExtractorConfig.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (is == null) {
                throw new IllegalStateException("application.properties not found on classpath");
            }
            props.load(is);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load application.properties", e);
        }

        String extractionMode = props.getProperty("s4hana.extraction.mode", "full");
        if (!"full".equalsIgnoreCase(extractionMode)
                && !"delta".equalsIgnoreCase(extractionMode)
                && !"full_no_delta".equalsIgnoreCase(extractionMode)) {
            throw new IllegalStateException(
                    "Invalid s4hana.extraction.mode='" + extractionMode
                    + "'. Must be 'full', 'delta', or 'full_no_delta'.");
        }

        String pathsCsv = props.getProperty("s4hana.service.paths", "");
        List<String> servicePaths = pathsCsv.isBlank()
                ? Collections.emptyList()
                : Arrays.stream(pathsCsv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();

        return new ExtractorConfig(
                props.getProperty("s4hana.url"),
                props.getProperty("s4hana.user"),
                props.getProperty("s4hana.password"),
                props.getProperty("s4hana.client", "100"),
                props.getProperty("s4hana.prefer", "odata.maxpagesize=5000,odata.track-changes"),
                props.getProperty("s4hana.service.path"),
                props.getProperty("s4hana.entity.set"),
                extractionMode,
                Integer.parseInt(props.getProperty("s4hana.parallel.calls", "5")),
                0,
                servicePaths
        );
    }

    public String getBaseUrl()        { return baseUrl; }
    public String getUser()           { return user; }
    public String getPassword()       { return password; }
    public String getClient()         { return client; }
    public String getPreferHeader() {
        if (isFullNoDelta()) {
            // Strip odata.track-changes from Prefer header
            return java.util.Arrays.stream(preferHeader.split(","))
                    .map(String::trim)
                    .filter(p -> !p.equalsIgnoreCase("odata.track-changes"))
                    .reduce((a, b) -> a + "," + b)
                    .orElse("");
        }
        return preferHeader;
    }
    public String getServicePath()    { return servicePath; }
    /** Returns the full list of available service paths (primary first). */
    public List<String> getServicePaths() { return servicePaths; }
    /** Returns true when the configured service path is OData v4 (heuristic: contains "/odata4/"). */
    public boolean isV4() {
        return servicePath != null && servicePath.toLowerCase().contains("/odata4/");
    }
    public String getEntitySet()      { return entitySet; }
    public String getExtractionMode() { return extractionMode; }
    public boolean isDeltaLoad()      { return "delta".equalsIgnoreCase(extractionMode); }
    public boolean isFullNoDelta()    { return "full_no_delta".equalsIgnoreCase(extractionMode); }
    public boolean isDeltaSaveEnabled() { return !isFullNoDelta(); }
    public int getParallelCalls()     { return parallelCalls; }
    /** Returns user-supplied page size override, or 0 to fall back to the Prefer header value. */
    public int getPageSizeOverride()  { return pageSizeOverride; }

    /**
     * Returns a new config with the given entity set, extraction mode, parallel calls, and page-size override.
     */
    public ExtractorConfig withOverrides(String entitySet, String extractionMode, int parallelCalls, int pageSizeOverride) {
        return new ExtractorConfig(
                this.baseUrl, this.user, this.password, this.client,
                this.preferHeader, this.servicePath, entitySet, extractionMode,
                parallelCalls, pageSizeOverride, this.servicePaths);
    }

    /** Returns a new config with a different service path (entity sets and other settings preserved). */
    public ExtractorConfig withServicePath(String newServicePath) {
        if (newServicePath == null || newServicePath.isBlank()) return this;
        return new ExtractorConfig(
                this.baseUrl, this.user, this.password, this.client,
                this.preferHeader, newServicePath, this.entitySet, this.extractionMode,
                this.parallelCalls, this.pageSizeOverride, this.servicePaths);
    }

    /**
     * Returns a new config with optional per-service connection overrides applied.
     * Any null/blank parameter falls back to this instance's current value.
     */
    public ExtractorConfig withConnection(String newServicePath, String newBaseUrl,
                                          String newUser, String newPassword,
                                          String newClient, String newPreferHeader) {
        String svc = (newServicePath == null || newServicePath.isBlank()) ? this.servicePath : newServicePath;
        return new ExtractorConfig(
                (newBaseUrl == null || newBaseUrl.isBlank()) ? this.baseUrl : newBaseUrl,
                (newUser == null || newUser.isBlank()) ? this.user : newUser,
                (newPassword == null || newPassword.isBlank()) ? this.password : newPassword,
                (newClient == null || newClient.isBlank()) ? this.client : newClient,
                (newPreferHeader == null || newPreferHeader.isBlank()) ? this.preferHeader : newPreferHeader,
                svc, this.entitySet, this.extractionMode,
                this.parallelCalls, this.pageSizeOverride, this.servicePaths);
    }

    /**
     * Returns a new config with the given entity set and extraction mode,
     * keeping all other settings from this instance.
     */
    public ExtractorConfig withOverrides(String entitySet, String extractionMode) {
        return new ExtractorConfig(
                this.baseUrl, this.user, this.password, this.client,
                this.preferHeader, this.servicePath, entitySet, extractionMode,
                this.parallelCalls);
    }

    /**
     * Returns a new config with the given entity set, extraction mode, and parallel calls override,
     * keeping all other settings from this instance.
     */
    public ExtractorConfig withOverrides(String entitySet, String extractionMode, int parallelCalls) {
        return new ExtractorConfig(
                this.baseUrl, this.user, this.password, this.client,
                this.preferHeader, this.servicePath, entitySet, extractionMode,
                parallelCalls);
    }
}
