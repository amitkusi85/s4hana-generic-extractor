package com.example;

import com.example.config.ExtractorConfig;
import com.example.db.JobHistoryRepository;
import com.example.extractor.HttpClient;
import com.example.extractor.MetadataDiscovery;
import com.example.extractor.ODataExtractor;
import com.example.extractor.ServiceDiscovery;
import com.example.writer.CsvFileWriter;
import com.example.writer.JsonFileWriter;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lightweight web UI for the S/4HANA Generic Extractor.
 * Uses Java's built-in HTTP server — no external web framework needed.
 *
 * Endpoints:
 *   GET  /             → HTML UI
 *   GET  /api/entities → list available entity sets
 *   POST /api/extract  → run extraction for selected entity sets
 *   GET  /api/jobs     → check running/completed job statuses
 */
public class WebUIServer {

    private static final Logger logger = LoggerFactory.getLogger(WebUIServer.class);
    private static final Gson gson = new Gson();
    private static final AtomicInteger jobIdCounter = new AtomicInteger(0);
    private static final Map<Integer, JobStatus> jobs = new ConcurrentHashMap<>();
    private static final JobHistoryRepository repo = new JobHistoryRepository();
    static {
        repo.initSchema();
        // Seed the counter past any previously persisted job_id so restarts don't reuse IDs.
        jobIdCounter.set(repo.getMaxJobId());
    }

    private final ExtractorConfig baseConfig;
    private final int port;

    public WebUIServer(ExtractorConfig baseConfig, int port) {
        this.baseConfig = baseConfig;
        this.port = port;
    }

    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        server.setExecutor(executor);

        server.createContext("/", this::handleIndex);
        server.createContext("/api/services", this::handleServices);
        server.createContext("/api/entities", this::handleEntities);
        server.createContext("/api/extract", this::handleExtract);
        server.createContext("/api/jobs", this::handleJobs);
        server.createContext("/api/history", this::handleHistory);
        server.createContext("/api/logs", this::handleLogs);
        server.createContext("/api/delta/status", this::handleDeltaStatus);
        server.createContext("/api/delta/reset", this::handleDeltaReset);

        server.start();
        logger.info("Web UI started at http://localhost:{}", port);
    }

    // ── HTML UI ───────────────────────────────────────────────────────────

    private void handleIndex(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if (path == null || path.equals("/") || path.equals("/index.html")) {
            serveStatic(exchange, "/web/index.html", "text/html; charset=utf-8");
        } else if (path.equals("/app.css")) {
            serveStatic(exchange, "/web/app.css", "text/css; charset=utf-8");
        } else if (path.equals("/app.js")) {
            serveStatic(exchange, "/web/app.js", "application/javascript; charset=utf-8");
        } else {
            sendResponse(exchange, 404, "text/plain", "Not Found");
        }
    }

    private void serveStatic(HttpExchange exchange, String resourcePath, String contentType) throws IOException {
        try (InputStream in = WebUIServer.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                sendResponse(exchange, 404, "text/plain", "Not Found: " + resourcePath);
                return;
            }
            byte[] bytes = in.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    // ── REST: list configured services ────────────────────────────────────

    private void handleServices(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        JsonObject response = new JsonObject();
        JsonArray arr = new JsonArray();
        baseConfig.getServicePaths().forEach(arr::add);
        response.add("servicePaths", arr);
        response.addProperty("defaultServicePath", baseConfig.getServicePath());
        sendResponse(exchange, 200, "application/json", gson.toJson(response));
    }

    // ── REST: list entity sets ────────────────────────────────────────────

    private void handleEntities(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try {
            String requestedService = getQueryParam(exchange, "service");
            ExtractorConfig effective = (requestedService == null || requestedService.isBlank())
                    ? baseConfig
                    : baseConfig.withServicePath(requestedService);
            HttpClient httpClient = new HttpClient(effective);
            ServiceDiscovery discovery = new ServiceDiscovery(effective, httpClient);
            List<String> entities = discovery.discoverEntitySets();
            // Filter out delta-link helper entity sets — not useful for extraction
            entities.removeIf(name -> name != null && name.toLowerCase().startsWith("deltalinksof"));

            JsonObject response = new JsonObject();
            response.addProperty("servicePath", effective.getServicePath());
            JsonArray arr = new JsonArray();
            entities.forEach(arr::add);
            response.add("entitySets", arr);

            sendResponse(exchange, 200, "application/json", gson.toJson(response));
        } catch (Exception e) {
            logger.error("Failed to discover entity sets", e);
            JsonObject err = new JsonObject();
            err.addProperty("error", e.getMessage());
            sendResponse(exchange, 500, "application/json", gson.toJson(err));
        }
    }

    // ── REST: run extraction ──────────────────────────────────────────────

    private void handleExtract(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonObject request = gson.fromJson(body, JsonObject.class);

        JsonArray entitySetsArr = request.getAsJsonArray("entitySets");
        String mode = request.has("mode") ? request.get("mode").getAsString() : "full";
        String format = request.has("format") ? request.get("format").getAsString() : "csv";
        int parallelCalls = request.has("parallelCalls") && !request.get("parallelCalls").isJsonNull()
                ? Math.max(1, request.get("parallelCalls").getAsInt())
                : baseConfig.getParallelCalls();
        int pageSize = request.has("pageSize") && !request.get("pageSize").isJsonNull()
                ? Math.max(1, request.get("pageSize").getAsInt())
                : 5000;
        boolean force = request.has("force") && !request.get("force").isJsonNull()
                && request.get("force").getAsBoolean();
        String servicePath = request.has("service") && !request.get("service").isJsonNull()
                ? request.get("service").getAsString()
                : null;
        // OData v4 services do not support the v2-style delta-token mechanism.
        // Force full_no_delta regardless of what was requested.
        if (servicePath != null && servicePath.toLowerCase().contains("/odata4/")
                && !"full_no_delta".equalsIgnoreCase(mode)) {
            logger.info("Forcing mode=full_no_delta for OData v4 service {}", servicePath);
            mode = "full_no_delta";
        }

        if (entitySetsArr == null || entitySetsArr.isEmpty()) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "No entity sets selected");
            sendResponse(exchange, 400, "application/json", gson.toJson(err));
            return;
        }

        List<String> entitySets = new ArrayList<>();
        entitySetsArr.forEach(e -> entitySets.add(e.getAsString()));

        // Check for already-running jobs on the same entity set
        List<String> conflictEntities = new ArrayList<>();
        Map<String, Integer> conflictJobIds = new LinkedHashMap<>();
        for (String es : entitySets) {
            for (JobStatus j : jobs.values()) {
                if (es.equals(j.entitySet)
                        && ("queued".equals(j.state) || "running".equals(j.state))) {
                    conflictEntities.add(es);
                    conflictJobIds.put(es, j.id);
                    break;
                }
            }
        }

        if (!conflictEntities.isEmpty() && !force) {
            JsonObject conflict = new JsonObject();
            conflict.addProperty("conflict", true);
            JsonArray names = new JsonArray();
            conflictEntities.forEach(names::add);
            conflict.add("entitySets", names);
            JsonArray ids = new JsonArray();
            conflictJobIds.values().forEach(ids::add);
            conflict.add("jobIds", ids);
            conflict.addProperty("message",
                "Extraction already running for: " + String.join(", ", conflictEntities));
            sendResponse(exchange, 409, "application/json", gson.toJson(conflict));
            return;
        }

        // If force=true, cancel running jobs for the conflicting entity sets
        if (force) {
            for (Integer cid : conflictJobIds.values()) {
                cancelJob(cid);
            }
        }

        // Create a job for each entity set
        final String effectiveMode = mode;
        List<Integer> jobIds = new ArrayList<>();
        for (String entitySet : entitySets) {
            int jobId = jobIdCounter.incrementAndGet();
            JobStatus status = new JobStatus(jobId, entitySet, effectiveMode);
            jobs.put(jobId, status);
            jobIds.add(jobId);
            repo.insertJob(jobId, entitySet, effectiveMode);
            // Emit a 'queued' job log entry immediately so the row is visible in logs
            // before the worker thread transitions the job to 'running'.
            status.log("Job queued for " + effectiveMode + " extraction of " + entitySet);

            String outputFormat = format;
            int workerCount = parallelCalls;
            int workerPageSize = pageSize;
            String svcPath = servicePath;
            Thread t = new Thread(() -> runExtraction(status, entitySet, effectiveMode, outputFormat, workerCount, workerPageSize, svcPath));
            status.thread = t;
            t.start();
        }

        JsonObject response = new JsonObject();
        JsonArray ids = new JsonArray();
        jobIds.forEach(ids::add);
        response.add("jobIds", ids);
        response.addProperty("message", "Started " + entitySets.size() + " extraction(s)");
        sendResponse(exchange, 202, "application/json", gson.toJson(response));
    }

    private void cancelJob(int jobId) {
        JobStatus s = jobs.get(jobId);
        if (s == null) return;
        s.cancelled = true;
        if (s.thread != null) s.thread.interrupt();
        s.state = "cancelled";
        s.log("WARN", "Cancelled by user (superseded by new extraction)");
        repo.updateJob(jobId, "cancelled", s.recordCount, s.outputFile, "Cancelled by user");
    }

    private void runExtraction(JobStatus status, String entitySet, String mode, String format, int parallelCalls, int pageSize, String servicePath) {
        try {
            status.state = "running";
            // Persist the running state so /api/history reflects it (insertJob writes 'queued')
            repo.updateJob(status.id, "running", 0, null, null);
            status.log("Starting " + mode + " extraction for " + entitySet
                    + ("full_no_delta".equalsIgnoreCase(mode) ? " with " + parallelCalls + " parallel worker(s), pageSize=" + pageSize : "")
                    + (servicePath != null && !servicePath.isBlank() ? " via service " + servicePath : ""));

            ExtractorConfig svcCfg = (servicePath == null || servicePath.isBlank())
                    ? baseConfig
                    : baseConfig.withServicePath(servicePath);
            ExtractorConfig config = svcCfg.withOverrides(entitySet, mode, parallelCalls, pageSize);
            HttpClient httpClient = new HttpClient(config);

            status.log("Discovering fields from $metadata...");
            MetadataDiscovery metadata = new MetadataDiscovery(config, httpClient);
            List<String> fields = metadata.discoverFields();
            status.log("Discovered " + fields.size() + " fields");

            ODataExtractor extractor = new ODataExtractor(config, httpClient, fields);
            extractor.setJobContext(repo, status.id);
            Path outputPath = Paths.get("output");
            outputPath.toFile().mkdirs();

            String timestamp = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String modeLabel = "delta".equalsIgnoreCase(mode) ? "delta"
                    : "full_no_delta".equalsIgnoreCase(mode) ? "full_no_delta" : "full";
            String prefix = entitySet.toLowerCase() + "_" + modeLabel + "_load_" + timestamp;
            Path outputFile = outputPath.resolve(prefix + "." + format);

            int count;
            if ("full_no_delta".equalsIgnoreCase(mode) && "csv".equalsIgnoreCase(format)) {
                ODataExtractor.ParallelResult pr = extractor.extractParallelToCsvFiles(outputPath, prefix);
                count = pr.totalCount;
                if (!pr.partFiles.isEmpty()) {
                    status.log("Merging " + pr.partFiles.size() + " part file(s) into " + outputFile.getFileName());
                    com.example.writer.CsvFileMerger.merge(pr.partFiles, outputFile, true);
                } else {
                    java.nio.file.Files.createFile(outputFile);
                }
            } else if ("json".equalsIgnoreCase(format)) {
                List<Map<String, String>> allData = new ArrayList<>();
                count = extractor.extract(allData::addAll);
                new JsonFileWriter().write(allData, outputFile);
            } else {
                try (CsvFileWriter csvWriter = new CsvFileWriter()) {
                    csvWriter.open(outputFile);
                    count = extractor.extract(batch -> {
                        try {
                            csvWriter.writeBatch(batch);
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to write CSV batch", e);
                        }
                    });
                }
            }

            status.recordCount = count;
            status.outputFile = outputFile.toAbsolutePath().toString();
            status.state = "completed";
            status.log("Extracted " + count + " records → " + outputFile.toAbsolutePath());
            repo.updateJob(status.id, "completed", count, status.outputFile, null);
        } catch (Exception e) {
            if (status.cancelled) {
                logger.info("Extraction for {} cancelled", entitySet);
                return;
            }
            status.state = "failed";
            status.error = e.getMessage();
            status.log("ERROR", "FAILED: " + e.getMessage());
            logger.error("Extraction failed for {}", entitySet, e);
            repo.updateJob(status.id, "failed", status.recordCount, status.outputFile, e.getMessage());
        }
    }

    // ── REST: job status ──────────────────────────────────────────────────

    private void handleJobs(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }

        String query = exchange.getRequestURI().getQuery();
        if (query != null && query.startsWith("id=")) {
            int id = Integer.parseInt(query.substring(3));
            JobStatus status = jobs.get(id);
            if (status == null) {
                sendResponse(exchange, 404, "application/json", "{\"error\":\"Job not found\"}");
            } else {
                sendResponse(exchange, 200, "application/json", gson.toJson(status));
            }
        } else {
            sendResponse(exchange, 200, "application/json", gson.toJson(jobs.values()));
        }
    }

    // ── REST: persisted job history (PostgreSQL) ─────────────────────

    private void handleHistory(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        JsonObject response = new JsonObject();
        response.addProperty("available", repo.isAvailable());
        response.add("rows", gson.toJsonTree(repo.getHistory()));
        sendResponse(exchange, 200, "application/json", gson.toJson(response));
    }

    // ── REST: structured job logs (PostgreSQL) ──────────────────────

    private void handleLogs(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        int jobId = -1;
        long sinceId = 0;
        int limit = 500;
        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            for (String p : query.split("&")) {
                int eq = p.indexOf('=');
                if (eq <= 0) continue;
                String k = p.substring(0, eq);
                String v = java.net.URLDecoder.decode(p.substring(eq + 1), StandardCharsets.UTF_8);
                try {
                    if ("jobId".equals(k))   jobId = Integer.parseInt(v);
                    else if ("since".equals(k)) sinceId = Long.parseLong(v);
                    else if ("limit".equals(k)) limit = Integer.parseInt(v);
                } catch (NumberFormatException ignore) {}
            }
        }
        JsonObject response = new JsonObject();
        response.addProperty("available", repo.isAvailable());
        response.add("rows", gson.toJsonTree(repo.getLogs(jobId, sinceId, limit)));
        sendResponse(exchange, 200, "application/json", gson.toJson(response));
    }

    // ── REST: delta subscription management (SAP function imports) ──────

    private void handleDeltaStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        String entitySet = getEntitySetParam(exchange);
        String service = getQueryParam(exchange, "service");
        invokeFunctionImport(exchange, "SubscribedTo" + entitySet, service);
    }

    private void handleDeltaReset(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        String entitySet = getEntitySetParam(exchange);
        String service = getQueryParam(exchange, "service");
        invokeFunctionImport(exchange, "TerminateDeltasFor" + entitySet, service);
    }

    private String getEntitySetParam(HttpExchange exchange) {
        String v = getQueryParam(exchange, "entitySet");
        return (v == null || v.isBlank()) ? baseConfig.getEntitySet() : v;
    }

    private String getQueryParam(HttpExchange exchange, String key) {
        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            for (String p : query.split("&")) {
                int eq = p.indexOf('=');
                if (eq > 0 && key.equals(p.substring(0, eq))) {
                    return java.net.URLDecoder.decode(p.substring(eq + 1), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    private void invokeFunctionImport(HttpExchange exchange, String functionName, String servicePath) throws IOException {
        ExtractorConfig effective = (servicePath == null || servicePath.isBlank())
                ? baseConfig
                : baseConfig.withServicePath(servicePath);
        String url = effective.getBaseUrl() + effective.getServicePath()
                + "/" + functionName
                + "?$format=json&sap-client=" + effective.getClient();
        logger.info("Invoking function import: {}", url);
        JsonObject response = new JsonObject();
        response.addProperty("functionName", functionName);
        response.addProperty("url", url);
        try {
            HttpClient client = new HttpClient(effective);
            String body = client.executeRequest(url);
            response.addProperty("success", true);
            // Try to embed parsed JSON; fall back to raw text
            try {
                response.add("result", com.google.gson.JsonParser.parseString(body));
            } catch (Exception parseEx) {
                response.addProperty("result", body);
            }
            sendResponse(exchange, 200, "application/json", gson.toJson(response));
        } catch (Exception e) {
            logger.error("Function import {} failed", functionName, e);
            response.addProperty("success", false);
            response.addProperty("error", e.getMessage());
            sendResponse(exchange, 500, "application/json", gson.toJson(response));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void sendResponse(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ── Job tracking ─────────────────────────────────────────────────────

    static class JobStatus {
        int id;
        String entitySet;
        String mode;
        String state = "queued";   // queued → running → completed / failed / cancelled
        int recordCount;
        String outputFile;
        String error;
        volatile boolean cancelled = false;
        transient Thread thread;
        List<String> logs = Collections.synchronizedList(new ArrayList<>());

        JobStatus(int id, String entitySet, String mode) {
            this.id = id;
            this.entitySet = entitySet;
            this.mode = mode;
        }

        void log(String message) {
            log("INFO", message);
        }

        void log(String level, String message) {
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            logs.add("[" + ts + "] " + message);
            logger.info("[Job {}] {}", id, message);
            // Persist structured entry (no-op if DB is unavailable)
            repo.appendLog(id, level, message);
        }
    }

    // ── Embedded HTML moved to src/main/resources/web/{index.html, app.css, app.js} ──
    // Served by handleIndex() / serveStatic() above.

}
