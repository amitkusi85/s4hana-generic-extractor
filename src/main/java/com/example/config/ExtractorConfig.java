package com.example.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

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

    public ExtractorConfig(String baseUrl, String user, String password, String client,
                           String preferHeader, String servicePath, String entitySet,
                           String extractionMode, int parallelCalls) {
        this.baseUrl = baseUrl;
        this.user = user;
        this.password = password;
        this.client = client;
        this.preferHeader = preferHeader;
        this.servicePath = servicePath;
        this.entitySet = entitySet;
        this.extractionMode = extractionMode;
        this.parallelCalls = parallelCalls;
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

        return new ExtractorConfig(
                props.getProperty("s4hana.url"),
                props.getProperty("s4hana.user"),
                props.getProperty("s4hana.password"),
                props.getProperty("s4hana.client", "100"),
                props.getProperty("s4hana.prefer", "odata.maxpagesize=5000,odata.track-changes"),
                props.getProperty("s4hana.service.path"),
                props.getProperty("s4hana.entity.set"),
                extractionMode,
                Integer.parseInt(props.getProperty("s4hana.parallel.calls", "5"))
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
    public String getEntitySet()      { return entitySet; }
    public String getExtractionMode() { return extractionMode; }
    public boolean isDeltaLoad()      { return "delta".equalsIgnoreCase(extractionMode); }
    public boolean isFullNoDelta()    { return "full_no_delta".equalsIgnoreCase(extractionMode); }
    public boolean isDeltaSaveEnabled() { return !isFullNoDelta(); }
    public int getParallelCalls()     { return parallelCalls; }

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
}
