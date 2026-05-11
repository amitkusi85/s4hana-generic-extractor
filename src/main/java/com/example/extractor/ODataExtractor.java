package com.example.extractor;

import com.example.config.ExtractorConfig;
import com.example.db.JobHistoryRepository;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

    /** Log to slf4j and (when a job context is set) persist to job_logs. */
    private void jobLog(String level, String message) {
        if ("WARN".equalsIgnoreCase(level)) logger.warn(message);
        else logger.info(message);
        if (repo != null && jobId > 0 && repo.isAvailable()) {
            repo.appendLog(jobId, level, message);
        }
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

    /** Result of a parallel-to-files extraction. */
    public static class ParallelResult {
        public final int totalCount;
        public final List<java.nio.file.Path> partFiles;
        public ParallelResult(int totalCount, List<java.nio.file.Path> partFiles) {
            this.totalCount = totalCount;
            this.partFiles = partFiles;
        }
    }

    /**
     * Parallel $top/$skip extraction where each worker writes its own CSV file.
     * Worker i fetches offsets [i*pageSize, (i+N)*pageSize, (i+2N)*pageSize, ...].
     * Returns the list of part files (one per worker that produced rows) and total record count.
     */
    public ParallelResult extractParallelToCsvFiles(java.nio.file.Path outDir, String prefix) throws Exception {
        int requestedPageSize = getPageSize();
        int parallelCalls = Math.max(1, config.getParallelCalls());
        String select = String.join(",", fields);
        String base = config.getBaseUrl() + config.getServicePath() + "/" + config.getEntitySet();
        boolean v4 = config.isV4();

        // For v4 the SAP gateway often caps server-side page size below the requested $top.
        // Probe one page to discover the effective cap, then use it as both $top and the worker stride.
        int pageSize = requestedPageSize;
        if (v4) {
            String probeUrl = base + "?$select=" + select + "&$top=" + requestedPageSize + "&$skip=0";
            jobLog("INFO", "[probe] Detecting effective page size: " + probeUrl);
            String probeBody = httpClient.executeRequest(probeUrl);
            List<Map<String, String>> probeBatch = parseEntriesJson(probeBody);
            int returned = probeBatch.size();
            if (returned == 0) {
                jobLog("INFO", "[probe] Server returned 0 rows. Nothing to extract.");
                return new ParallelResult(0, java.util.Collections.emptyList());
            }
            // If the server honored the request, keep the configured size; otherwise drop to the cap.
            pageSize = (returned < requestedPageSize) ? returned : requestedPageSize;
            jobLog("INFO", "[probe] Server returned " + returned + " rows (requested " + requestedPageSize
                    + "). Effective pageSize=" + pageSize);
        }

        jobLog("INFO", "Full Load (delta disabled, per-worker files): pageSize=" + pageSize
                + ", parallelCalls=" + parallelCalls + (v4 ? " (OData v4 / JSON)" : ""));

        ExecutorService executor = Executors.newFixedThreadPool(parallelCalls);
        List<Future<int[]>> futures = new ArrayList<>();
        java.nio.file.Path[] partFiles = new java.nio.file.Path[parallelCalls];

        try {
            final int effectivePageSize = pageSize;
            for (int w = 0; w < parallelCalls; w++) {
                final int workerIndex = w;
                final java.nio.file.Path partFile = outDir.resolve(prefix + "_part" + workerIndex + ".csv");
                partFiles[w] = partFile;
                futures.add(executor.submit(() -> runWorker(workerIndex, parallelCalls, effectivePageSize, base, select, partFile)));
            }

            int total = 0;
            List<java.nio.file.Path> produced = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                int[] r = futures.get(i).get();
                int count = r[0];
                total += count;
                if (count > 0) {
                    produced.add(partFiles[i]);
                } else {
                    // Worker produced no rows; remove empty file if it was created
                    try { java.nio.file.Files.deleteIfExists(partFiles[i]); } catch (Exception ignore) {}
                }
            }
            jobLog("INFO", "All " + parallelCalls + " workers complete. Total records=" + total
                    + ", part files=" + produced.size());
            return new ParallelResult(total, produced);
        } finally {
            executor.shutdown();
        }
    }

    private int[] runWorker(int idx, int parallelCalls, int pageSize, String base, String select,
                            java.nio.file.Path partFile) throws Exception {
        boolean v4 = config.isV4();
        DocumentBuilder builder = null;
        if (!v4) {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            builder = dbf.newDocumentBuilder();
        }

        int count = 0;
        try (com.example.writer.CsvFileWriter writer = new com.example.writer.CsvFileWriter()) {
            writer.open(partFile);
            int skip = idx * pageSize;
            while (true) {
                String url = base + "?$select=" + select + "&$top=" + pageSize + "&$skip=" + skip;
                jobLog("INFO", "[worker " + idx + "] Fetching: " + url);
                String responseBody = httpClient.executeRequest(url);
                List<Map<String, String>> batch;
                if (v4) {
                    batch = parseEntriesJson(responseBody);
                } else {
                    Document doc = builder.parse(new InputSource(new StringReader(responseBody)));
                    batch = parseEntries(doc);
                }
                if (batch.isEmpty()) break;

                writer.writeBatch(batch);
                count += batch.size();
                jobLog("INFO", "[worker " + idx + "] fetched " + count + " rows so far (skip=" + skip + ")");

                // For v4, only stop on an empty page (the server may cap any page below the requested $top).
                // For v2, a short page reliably means end-of-data and we can stop early.
                if (!v4 && batch.size() < pageSize) break;
                skip += parallelCalls * pageSize;
            }
        }
        return new int[]{count};
    }

    /**
     * Pagination via $top and $skip with parallel calls (used for full_no_delta mode).
     */
    private int extractWithTopSkip(Consumer<List<Map<String, String>>> batchConsumer,
                                    DocumentBuilder builder) throws Exception {
        int requestedPageSize = getPageSize();
        int parallelCalls = config.getParallelCalls();
        String select = String.join(",", fields);
        String base = config.getBaseUrl() + config.getServicePath() + "/" + config.getEntitySet();
        boolean v4 = config.isV4();

        // For v4 the SAP gateway often caps server-side page size below the requested $top.
        // Probe one page to discover the effective cap, then use it as both $top and the worker stride.
        int pageSize = requestedPageSize;
        if (v4) {
            String probeUrl = base + "?$select=" + select + "&$top=" + requestedPageSize + "&$skip=0";
            jobLog("INFO", "[probe] Detecting effective page size: " + probeUrl);
            String probeBody = httpClient.executeRequest(probeUrl);
            List<Map<String, String>> probeBatch = parseEntriesJson(probeBody);
            int returned = probeBatch.size();
            if (returned == 0) {
                jobLog("INFO", "[probe] Server returned 0 rows. Nothing to extract.");
                return 0;
            }
            pageSize = (returned < requestedPageSize) ? returned : requestedPageSize;
            jobLog("INFO", "[probe] Server returned " + returned + " rows (requested " + requestedPageSize
                    + "). Effective pageSize=" + pageSize);
            // Emit the probe batch so we don't refetch skip=0
            batchConsumer.accept(probeBatch);
        }

        jobLog("INFO", "Full Load (delta disabled): $top/$skip pagination, pageSize=" + pageSize
                + ", parallelCalls=" + parallelCalls + (v4 ? " (OData v4 / JSON)" : ""));

        ExecutorService executor = Executors.newFixedThreadPool(parallelCalls);
        int totalCount = v4 ? pageSize : 0; // already consumed the probe page
        // Start the next round at skip = pageSize for v4 (probe consumed [0, pageSize)); skip=0 for v2.
        int skip = v4 ? pageSize : 0;
        boolean done = false;
        final int finalPageSize = pageSize;

        try {
            while (!done) {
                // Submit a batch of parallel page requests
                List<Future<List<Map<String, String>>>> futures = new ArrayList<>();
                int[] offsets = new int[parallelCalls];
                for (int i = 0; i < parallelCalls; i++) {
                    offsets[i] = skip + i * finalPageSize;
                    int currentSkip = offsets[i];
                    futures.add(executor.submit(() -> {
                        String url = base + "?$select=" + select + "&$top=" + finalPageSize + "&$skip=" + currentSkip;
                        jobLog("INFO", "Fetching: " + url);
                        String responseBody = httpClient.executeRequest(url);
                        if (v4) {
                            return parseEntriesJson(responseBody);
                        }
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
                    jobLog("INFO", "Fetched " + totalCount + " records so far (skip=" + offsets[i] + ")");

                    // For v4, only stop on an empty page (server may cap any page below requested $top).
                    // For v2, a short page reliably means end-of-data.
                    if (!v4 && batch.size() < finalPageSize) {
                        done = true;
                        break;
                    }
                }

                skip += parallelCalls * finalPageSize;
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
            jobLog("INFO", "Fetching: " + url);
            String responseBody = httpClient.executeRequest(url);

            Document doc = builder.parse(new InputSource(new StringReader(responseBody)));
            List<Map<String, String>> batch = parseEntries(doc);

            totalCount += batch.size();
            batchConsumer.accept(batch);
            jobLog("INFO", "Fetched " + totalCount + " records so far");

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

    /** Parse an OData v4 JSON page: {"value":[{...},...], "@odata.nextLink": ...?}. */
    private List<Map<String, String>> parseEntriesJson(String body) {
        if (body == null || body.isBlank()) return Collections.emptyList();
        JsonElement root;
        try {
            root = JsonParser.parseString(body);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse v4 JSON response: " + e.getMessage()
                    + " (first 200 chars: " + body.substring(0, Math.min(body.length(), 200)) + ")", e);
        }
        if (!root.isJsonObject()) return Collections.emptyList();
        JsonObject obj = root.getAsJsonObject();
        if (!obj.has("value") || !obj.get("value").isJsonArray()) return Collections.emptyList();
        JsonArray arr = obj.getAsJsonArray("value");
        List<Map<String, String>> batch = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JsonElement el = arr.get(i);
            if (!el.isJsonObject()) continue;
            JsonObject row = el.getAsJsonObject();
            Map<String, String> map = new LinkedHashMap<>();
            for (String field : fields) {
                JsonElement v = row.get(field);
                String s = "";
                if (v != null && !v.isJsonNull()) {
                    s = v.isJsonPrimitive() ? v.getAsString() : v.toString();
                }
                map.put(field, s);
            }
            batch.add(map);
        }
        return batch;
    }

    private int getPageSize() {
        // Explicit per-job override wins
        if (config.getPageSizeOverride() > 0) {
            return config.getPageSizeOverride();
        }
        // Otherwise parse from the Prefer header (odata.maxpagesize=N)
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
