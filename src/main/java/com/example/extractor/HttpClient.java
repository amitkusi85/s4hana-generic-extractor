package com.example.extractor;

import com.example.config.ExtractorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class HttpClient {

    private static final Logger logger = LoggerFactory.getLogger(HttpClient.class);
    private static final int MAX_RETRIES = 3;
    private static final int INITIAL_RETRY_DELAY_MS = 5000;

    private final ExtractorConfig config;
    private final String credentials;

    public HttpClient(ExtractorConfig config) {
        this.config = config;
        this.credentials = Base64.getEncoder()
                .encodeToString((config.getUser() + ":" + config.getPassword())
                        .getBytes(StandardCharsets.UTF_8));

        Authenticator.setDefault(new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(
                        config.getUser(), config.getPassword().toCharArray());
            }
        });
    }

    public String executeRequest(String urlStr) throws IOException {
        int retryDelay = INITIAL_RETRY_DELAY_MS;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) URI.create(urlStr).toURL().openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Authorization", "Basic " + credentials);
                conn.setRequestProperty("Prefer", config.getPreferHeader());
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(300000);

                int status = conn.getResponseCode();
                logger.debug("HTTP {} for {}", status, urlStr);

                if (status != 200) {
                    String errorBody = readStream(
                            conn.getErrorStream() != null ? conn.getErrorStream() : conn.getInputStream());
                    throw new IOException("OData request failed with HTTP " + status + ": " + errorBody);
                }

                return readStream(conn.getInputStream());
            } catch (IOException e) {
                if (attempt == MAX_RETRIES || !isRetryable(e)) {
                    throw e;
                }
                logger.warn("Request failed (attempt {}/{}): {}. Retrying in {}ms...",
                        attempt, MAX_RETRIES, e.getMessage(), retryDelay);
                try {
                    Thread.sleep(retryDelay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted during retry", ie);
                }
                retryDelay *= 2;
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        throw new IOException("Request failed after " + MAX_RETRIES + " attempts");
    }

    private boolean isRetryable(IOException e) {
        return e instanceof NoRouteToHostException
                || e instanceof ConnectException
                || e instanceof SocketTimeoutException
                || e instanceof SocketException;
    }

    private String readStream(java.io.InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }
}
