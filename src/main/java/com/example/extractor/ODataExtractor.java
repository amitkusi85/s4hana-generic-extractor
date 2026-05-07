package com.example.extractor;

import com.example.config.ExtractorConfig;
import com.example.db.JobHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Generic OData entity set extractor.
 * Fields are discovered at runtime via $metadata — nothing is hardcoded.
 */
public class ODataExtractor {

    private static final Logger logger = LoggerFactory.getLogger(ODataExtractor.class);

    private final ExtractorConfig config;
    private final HttpClient httpClient;
    private final List<String> fields;
    private JobHistoryRepository repo;
    private int jobId = -1;

    public ODataExtractor(ExtractorConfig config, HttpClient httpClient, List<String> fields) {
        this.config = config;
        this.httpClient = httpClient;
        this.fields = fields;
    }

    /**
     * Enable database-backed delta-token persistence.
     * When set, {@link #loadDeltaToken()} reads the latest token from {@code job_history}
     * for this entity set, and {@link #saveDeltaToken(String)} writes it back against this jobId.
     */
    public void setJobContext(JobHistoryRepository repo, int jobId) {
        this.repo = repo;
        this.jobId = jobId;
    }

    /**
     * Extracts records in a streaming fashion.
     * Each page of results is passed to the batchConsumer as it arrives.
     *
     * @param batchConsumer receives each page of parsed records
     * @return total number of records extracted
     */
    public int extract(Consumer<List<Map<String, String>>> batchConsumer) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder builder = factory.newDocumentBuilder();

        if (config.isFullNoDelta()) {
            return extractWithTopSkip(batchConsumer, builder);
        } else {
            return extractWithServerPaging(batchConsumer, builder);
        }
    }

    /**
     * Pagination via $top and $skip with parallel calls (used for full_no_delta mode).
     */
    private int extractWithTopSkip(Consumer<List<Map<String, String>>> batchConsumer,
                                    DocumentBuilder builder) throws Exception {
        int pageSize = getPageSize();
        int parallelCalls = config.getParallelCalls();
        String select = String.join(",", fields);
        String base = config.getBaseUrl() + config.getServicePath() + "/" + config.getEntitySet();

        logger.info("Full Load (delta disabled): $top/$skip pagination, pageSize={}, parallelCalls={}",
                pageSize, parallelCalls);

        ExecutorService executor = Executors.newFixedThreadPool(parallelCalls);
        int totalCount = 0;
        int skip = 0;
        boolean done = false;

        try {
            while (!done) {
                // Submit a batch of parallel page requests
                List<Future<List<Map<String, String>>>> futures = new ArrayList<>();
                int[] offsets = new int[parallelCalls];
                for (int i = 0; i < parallelCalls; i++) {
                    offsets[i] = skip + i * pageSize;
                    int currentSkip = offsets[i];
                    futures.add(executor.submit(() -> {
                        String url = base + "?$select=" + select + "&$top=" + pageSize + "&$skip=" + currentSkip;
                        logger.info("Fetching: {}", url);
                        String responseBody = httpClient.executeRequest(url);
                        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                        dbf.setNamespaceAware(true);
                        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
                        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                        Document doc = dbf.newDocumentBuilder()
                                .parse(new InputSource(new StringReader(responseBody)));
                        return parseEntries(doc);
                    }));
                }

                // Collect results in order
                for (int i = 0; i < futures.size(); i++) {
                    List<Map<String, String>> batch = futures.get(i).get();
                    if (batch.isEmpty()) {
                        done = true;
                        break;
                    }
                    totalCount += batch.size();
                    batchConsumer.accept(batch);
                    logger.info("Fetched {} records so far (skip={})", totalCount, offsets[i]);

                    if (batch.size() < pageSize) {
                        done = true;
                        break;
                    }
                }

                skip += parallelCalls * pageSize;
            }
        } finally {
            executor.shutdown();
        }

        return totalCount;
    }

    /**
     * Pagination via server-side next links (used for full and delta modes).
     */
    private int extractWithServerPaging(Consumer<List<Map<String, String>>> batchConsumer,
                                         DocumentBuilder builder) throws Exception {
        int totalCount = 0;
        String url = buildInitialUrl();

        while (url != null) {
            logger.info("Fetching: {}", url);
            String responseBody = httpClient.executeRequest(url);

            Document doc = builder.parse(new InputSource(new StringReader(responseBody)));
            List<Map<String, String>> batch = parseEntries(doc);

            totalCount += batch.size();
            batchConsumer.accept(batch);
            logger.info("Fetched {} records so far", totalCount);

            // Handle server-side paging and delta links
            url = null;
            NodeList links = doc.getElementsByTagNameNS("http://www.w3.org/2005/Atom", "link");
            for (int i = 0; i < links.getLength(); i++) {
                Element link = (Element) links.item(i);
                String rel = link.getAttribute("rel");
                if ("next".equals(rel)) {
                    String nextUrl = link.getAttribute("href");
                    if (!nextUrl.startsWith("http")) {
                        nextUrl = config.getBaseUrl() + config.getServicePath() + "/" + nextUrl;
                    }
                    url = nextUrl;
                } else if ("delta".equals(rel)) {
                    if (config.isDeltaSaveEnabled()) {
                        saveDeltaToken(link.getAttribute("href"));
                    } else {
                        logger.info("Delta token save disabled for this mode, skipping");
                    }
                }
            }
        }

        return totalCount;
    }

    private String buildInitialUrl() {
        String base = config.getBaseUrl() + config.getServicePath() + "/" + config.getEntitySet();

        if (config.isDeltaLoad()) {
            String deltaToken = loadDeltaToken();
            if (deltaToken != null && !deltaToken.isBlank()) {
                logger.info("Delta Load mode: using deltatoken={}", deltaToken);
                return base + "?!deltatoken='" + deltaToken + "'";
            } else {
                logger.warn("Delta Load mode requested but no deltatoken found, performing full load");
            }
        } else {
            logger.info("Full Load mode: no deltatoken sent");
        }

        String select = String.join(",", fields);
        return base + "?$select=" + select;
    }

    private String loadDeltaToken() {
        // 1) Prefer DB-backed token if a job context is configured
        if (repo != null && repo.isAvailable()) {
            String dbToken = repo.getLatestDeltaToken(config.getEntitySet());
            if (dbToken != null && !dbToken.isBlank()) {
                logger.info("Loaded delta token from job_history for {}", config.getEntitySet());
                return dbToken.trim();
            }
        }
        // 2) Fall back to legacy file-based token (for installs migrating from the old store)
        try {
            java.nio.file.Path tokenFile = java.nio.file.Paths.get(
                    "output", config.getEntitySet() + "_deltatoken.txt");
            if (java.nio.file.Files.exists(tokenFile)) {
                logger.info("Loaded delta token from legacy file {}", tokenFile.toAbsolutePath());
                return java.nio.file.Files.readString(tokenFile).trim();
            }
        } catch (Exception e) {
            logger.warn("Failed to read delta token: {}", e.getMessage());
        }
        return null;
    }

    private void saveDeltaToken(String deltaLink) {
        try {
            String deltaToken = deltaLink;
            if (deltaLink.contains("!deltatoken=")) {
                deltaToken = deltaLink.substring(
                        deltaLink.indexOf("!deltatoken=") + "!deltatoken=".length());
            }
            // Strip surrounding quotes if present
            deltaToken = deltaToken.replaceAll("^'+|'+$", "");

            // Persist to DB when a job context is set
            if (repo != null && repo.isAvailable() && jobId > 0) {
                repo.updateDeltaToken(jobId, deltaToken);
                logger.info("Delta token saved to job_history (jobId={}): {}", jobId, deltaToken);
                return;
            }

            // Legacy file fallback
            java.nio.file.Path tokenFile = java.nio.file.Paths.get(
                    "output", config.getEntitySet() + "_deltatoken.txt");
            tokenFile.getParent().toFile().mkdirs();
            java.nio.file.Files.writeString(tokenFile, deltaToken);
            logger.info("Delta token saved to {}: {}", tokenFile.toAbsolutePath(), deltaToken);
        } catch (Exception e) {
            logger.warn("Failed to save delta token: {}", e.getMessage());
        }
    }

    private List<Map<String, String>> parseEntries(Document doc) {
        NodeList entries = doc.getElementsByTagNameNS("http://www.w3.org/2005/Atom", "entry");
        List<Map<String, String>> batch = new ArrayList<>(entries.getLength());

        for (int i = 0; i < entries.getLength(); i++) {
            Element entry = (Element) entries.item(i);
            NodeList propsList = entry.getElementsByTagNameNS(
                    "http://schemas.microsoft.com/ado/2007/08/dataservices/metadata", "properties");
            if (propsList.getLength() == 0) continue;

            Element properties = (Element) propsList.item(0);
            Map<String, String> row = new LinkedHashMap<>();
            for (String field : fields) {
                NodeList fieldNodes = properties.getElementsByTagNameNS(
                        "http://schemas.microsoft.com/ado/2007/08/dataservices", field);
                String value = "";
                if (fieldNodes.getLength() > 0) {
                    value = fieldNodes.item(0).getTextContent();
                }
                row.put(field, value != null ? value : "");
            }
            batch.add(row);
        }
        return batch;
    }

    private int getPageSize() {
        // Parse page size from the Prefer header (odata.maxpagesize=N)
        String prefer = config.getPreferHeader();
        if (prefer != null) {
            for (String part : prefer.split(",")) {
                part = part.trim();
                if (part.toLowerCase().startsWith("odata.maxpagesize=")) {
                    try {
                        return Integer.parseInt(part.substring("odata.maxpagesize=".length()));
                    } catch (NumberFormatException e) {
                        logger.warn("Invalid odata.maxpagesize value, using default 5000");
                    }
                }
            }
        }
        return 5000;
    }
}
