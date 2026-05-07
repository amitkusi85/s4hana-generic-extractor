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
        sendResponse(exchange, 200, "text/html; charset=utf-8", INDEX_HTML);
    }

    // ── REST: list entity sets ────────────────────────────────────────────

    private void handleEntities(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try {
            HttpClient httpClient = new HttpClient(baseConfig);
            ServiceDiscovery discovery = new ServiceDiscovery(baseConfig, httpClient);
            List<String> entities = discovery.discoverEntitySets();
            // Filter out delta-link helper entity sets — not useful for extraction
            entities.removeIf(name -> name != null && name.toLowerCase().startsWith("deltalinksof"));

            JsonObject response = new JsonObject();
            response.addProperty("servicePath", baseConfig.getServicePath());
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

        if (entitySetsArr == null || entitySetsArr.isEmpty()) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "No entity sets selected");
            sendResponse(exchange, 400, "application/json", gson.toJson(err));
            return;
        }

        List<String> entitySets = new ArrayList<>();
        entitySetsArr.forEach(e -> entitySets.add(e.getAsString()));

        // Create a job for each entity set
        List<Integer> jobIds = new ArrayList<>();
        for (String entitySet : entitySets) {
            int jobId = jobIdCounter.incrementAndGet();
            JobStatus status = new JobStatus(jobId, entitySet, mode);
            jobs.put(jobId, status);
            jobIds.add(jobId);
            repo.insertJob(jobId, entitySet, mode);

            String outputFormat = format;
            new Thread(() -> runExtraction(status, entitySet, mode, outputFormat)).start();
        }

        JsonObject response = new JsonObject();
        JsonArray ids = new JsonArray();
        jobIds.forEach(ids::add);
        response.add("jobIds", ids);
        response.addProperty("message", "Started " + entitySets.size() + " extraction(s)");
        sendResponse(exchange, 202, "application/json", gson.toJson(response));
    }

    private void runExtraction(JobStatus status, String entitySet, String mode, String format) {
        try {
            status.state = "running";
            status.log("Starting " + mode + " extraction for " + entitySet);

            ExtractorConfig config = baseConfig.withOverrides(entitySet, mode);
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
            if ("json".equalsIgnoreCase(format)) {
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
        invokeFunctionImport(exchange, "SubscribedTo" + entitySet);
    }

    private void handleDeltaReset(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        String entitySet = getEntitySetParam(exchange);
        invokeFunctionImport(exchange, "TerminateDeltasFor" + entitySet);
    }

    private String getEntitySetParam(HttpExchange exchange) {
        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            for (String p : query.split("&")) {
                int eq = p.indexOf('=');
                if (eq > 0 && "entitySet".equals(p.substring(0, eq))) {
                    return java.net.URLDecoder.decode(p.substring(eq + 1), StandardCharsets.UTF_8);
                }
            }
        }
        return baseConfig.getEntitySet();
    }

    private void invokeFunctionImport(HttpExchange exchange, String functionName) throws IOException {
        String url = baseConfig.getBaseUrl() + baseConfig.getServicePath()
                + "/" + functionName
                + "?$format=json&sap-client=" + baseConfig.getClient();
        logger.info("Invoking function import: {}", url);
        JsonObject response = new JsonObject();
        response.addProperty("functionName", functionName);
        response.addProperty("url", url);
        try {
            HttpClient client = new HttpClient(baseConfig);
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
        String state = "queued";   // queued → running → completed / failed
        int recordCount;
        String outputFile;
        String error;
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

    // ── Embedded HTML ─────────────────────────────────────────────────────

    private static final String INDEX_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>S/4HANA Generic Extractor</title>
<style>
  :root {
    --primary: #0070f3;
    --primary-dark: #005bb5;
    --bg: #f5f7fa;
    --card: #ffffff;
    --border: #e2e8f0;
    --text: #1a202c;
    --text-muted: #718096;
    --success: #38a169;
    --danger: #e53e3e;
    --warning: #d69e2e;
  }
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body {
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
    background: var(--bg); color: var(--text); line-height: 1.6;
  }
  .header {
    background: linear-gradient(135deg, #1a365d 0%, #2a4a7f 100%);
    color: white; padding: 1.5rem 2rem;
  }
  .header h1 { font-size: 1.5rem; font-weight: 600; }
  .header p { opacity: 0.8; font-size: 0.9rem; }
  .container { max-width: 960px; margin: 2rem auto; padding: 0 1rem; }
  .card {
    background: var(--card); border: 1px solid var(--border);
    border-radius: 8px; padding: 1.5rem; margin-bottom: 1.5rem;
    box-shadow: 0 1px 3px rgba(0,0,0,0.06);
  }
  .card h2 {
    font-size: 1.1rem; margin-bottom: 1rem;
    padding-bottom: 0.5rem; border-bottom: 1px solid var(--border);
  }
  .toolbar {
    display: flex; gap: 1rem; align-items: center;
    flex-wrap: wrap; margin-bottom: 1rem;
  }
  .toolbar label { font-weight: 500; font-size: 0.9rem; }
  select, button {
    padding: 0.5rem 1rem; border-radius: 6px; border: 1px solid var(--border);
    font-size: 0.9rem; cursor: pointer;
  }
  select { background: white; }
  .btn-primary {
    background: var(--primary); color: white; border: none;
    font-weight: 600; transition: background 0.2s;
  }
  .btn-primary:hover { background: var(--primary-dark); }
  .btn-primary:disabled { opacity: 0.5; cursor: not-allowed; }
  .btn-refresh {
    background: white; border: 1px solid var(--border);
    font-weight: 500; transition: background 0.2s;
  }
  .btn-refresh:hover { background: var(--bg); }
  table { width: 100%; border-collapse: collapse; }
  th, td {
    text-align: left; padding: 0.6rem 0.8rem;
    border-bottom: 1px solid var(--border); font-size: 0.9rem;
  }
  th { background: var(--bg); font-weight: 600; font-size: 0.8rem;
       text-transform: uppercase; letter-spacing: 0.05em; color: var(--text-muted); }
  tr:hover { background: #f7fafc; }
  .cb-cell { width: 40px; text-align: center; }
  input[type="checkbox"] { width: 16px; height: 16px; cursor: pointer; }
  .mode-select { padding: 0.3rem 0.5rem; font-size: 0.85rem; }
  .status-badge {
    display: inline-block; padding: 0.2rem 0.6rem; border-radius: 12px;
    font-size: 0.75rem; font-weight: 600; text-transform: uppercase;
  }
  .badge-queued { background: #edf2f7; color: var(--text-muted); }
  .badge-running { background: #ebf8ff; color: #2b6cb0; }
  .badge-completed { background: #f0fff4; color: var(--success); }
  .badge-failed { background: #fff5f5; color: var(--danger); }
  .log-area {
    background: #1a202c; color: #a0aec0; font-family: 'Consolas', 'Monaco', monospace;
    font-size: 0.8rem; padding: 1rem; border-radius: 6px;
    max-height: 300px; overflow-y: auto; white-space: pre-wrap;
  }
  .log-area .log-success { color: #68d391; }
  .log-area .log-error { color: #fc8181; }
  .empty-state {
    text-align: center; padding: 2rem; color: var(--text-muted);
  }
  .select-all-row { background: var(--bg); }
  .spinner {
    display: inline-block; width: 14px; height: 14px;
    border: 2px solid #e2e8f0; border-top: 2px solid var(--primary);
    border-radius: 50%; animation: spin 0.8s linear infinite;
    vertical-align: middle; margin-right: 6px;
  }
  @keyframes spin { to { transform: rotate(360deg); } }
  #entityCount { font-size: 0.85rem; color: var(--text-muted); margin-left: auto; }
</style>
</head>
<body>

<div class="header">
  <h1>S/4HANA Generic Extractor</h1>
  <p>Select entity sets and run full or delta extractions</p>
</div>

<div class="container">

  <!-- Entity Selection -->
  <div class="card">
    <h2>Entity Sets</h2>
    <div class="toolbar">
      <label>Output Format:</label>
      <select id="formatSelect">
        <option value="csv" selected>CSV</option>
        <option value="json">JSON</option>
      </select>
      <button class="btn-refresh" onclick="loadEntities()">&#x21bb; Refresh</button>
      <button class="btn-primary" id="extractBtn" onclick="runExtraction()" disabled>
        &#x25B6; Run Extraction
      </button>
      <span id="entityCount"></span>
    </div>
    <div id="entityTable">
      <div class="empty-state">Click <strong>Refresh</strong> to load available entity sets from the server</div>
    </div>
    <div id="deltaArea" style="margin-top:12px"></div>
  </div>

  <!-- Job Status -->
  <div class="card">
    <h2>Live Extraction Jobs
      <span style="font-size:12px;font-weight:400;color:#666;margin-left:8px">(current session only — see Job History below for past runs)</span>
    </h2>
    <div id="jobsArea">
      <div class="empty-state">No extractions started in this session yet</div>
    </div>
  </div>

  <!-- Job History (persisted) -->
  <div class="card">
    <h2>Job History
      <button class="btn-refresh" style="float:right" onclick="loadHistory()">&#x21bb; Refresh</button>
    </h2>
    <div id="historyArea">
      <div class="empty-state">Click <strong>Refresh</strong> to load history</div>
    </div>
  </div>

</div>

<!-- Job Logs Modal (opened from Job History row) -->
<div id="logsModal" style="display:none;position:fixed;inset:0;background:rgba(0,0,0,0.5);z-index:1000;align-items:center;justify-content:center">
  <div style="background:#fff;width:90%;max-width:1100px;max-height:85vh;border-radius:8px;display:flex;flex-direction:column;box-shadow:0 10px 40px rgba(0,0,0,0.3)">
    <div style="padding:12px 18px;border-bottom:1px solid #e5e7eb;display:flex;align-items:center;justify-content:space-between">
      <h2 style="margin:0;font-size:16px">Job Logs &mdash; <span id="logsModalTitle">Job</span>
        <label style="font-size:12px;font-weight:400;color:#666;margin-left:14px">
          <input id="logAuto" type="checkbox" checked onchange="toggleAutoLogs()"> Auto-refresh (running jobs)
        </label>
      </h2>
      <span>
        <button class="btn-refresh" onclick="fetchLogs(false)">&#x21bb; Refresh</button>
        <button class="btn-refresh" style="background:#64748b;color:#fff;margin-left:6px" onclick="closeLogsModal()">Close</button>
      </span>
    </div>
    <div id="logArea" style="flex:1;overflow:auto;padding:12px 18px">
      <div class="empty-state">No logs yet</div>
    </div>
  </div>
</div>

<script>
let entities = [];
let activeJobIds = [];
let pollTimer = null;

async function callDelta(path, method, label) {
  const area = document.getElementById('deltaArea');
  area.innerHTML = '<div class="empty-state"><span class="spinner"></span> ' + label + '...</div>';
  try {
    const res = await fetch(path, { method: method });
    const data = await res.json();
    const ok = data.success;
    const color = ok ? 'var(--success,#22c55e)' : 'var(--danger,#ef4444)';
    let html = '<div style="margin-bottom:6px;color:' + color + ';font-weight:600">'
             + (ok ? 'OK' : 'FAILED') + ' — ' + (data.functionName || '') + '</div>'
             + '<div style="font-family:monospace;font-size:11px;color:#888;margin-bottom:6px;word-break:break-all">' + (data.url || '') + '</div>';
    if (ok) {
      const pretty = JSON.stringify(data.result, null, 2);
      html += '<pre style="background:#1e1e1e;color:#d4d4d4;padding:10px;border-radius:4px;overflow:auto;max-height:300px;font-size:12px">'
            + pretty.replace(/[<>&]/g, c => ({'<':'&lt;','>':'&gt;','&':'&amp;'}[c])) + '</pre>';
    } else {
      html += '<div style="color:var(--danger);font-family:monospace;font-size:12px;white-space:pre-wrap">' + (data.error || 'Unknown error') + '</div>';
    }
    area.innerHTML = html;
  } catch (e) {
    area.innerHTML = '<div class="empty-state" style="color:var(--danger)">Request failed: ' + e.message + '</div>';
  }
}

async function checkDeltaStatus(entity) {
  if (!entity) return;
  await callDelta('/api/delta/status?entitySet=' + encodeURIComponent(entity), 'GET', 'Checking subscription status for ' + entity);
}

async function resetDelta(entity) {
  if (!entity) return;
  if (!confirm('Terminate the delta subscription for "' + entity + '" on SAP?\\n\\nThis drops the change-tracking chain. The next delta run will start a fresh full load.')) return;
  await callDelta('/api/delta/reset?entitySet=' + encodeURIComponent(entity), 'POST', 'Terminating delta subscription for ' + entity);
}

async function loadHistory() {
  const area = document.getElementById('historyArea');
  area.innerHTML = '<div class="empty-state"><span class="spinner"></span> Loading...</div>';
  try {
    const res = await fetch('/api/history');
    const data = await res.json();
    if (!data.available) {
      area.innerHTML = '<div class="empty-state" style="color:var(--danger)">Database not reachable — history unavailable</div>';
      return;
    }
    if (!data.rows || data.rows.length === 0) {
      area.innerHTML = '<div class="empty-state">No historical jobs yet</div>';
      return;
    }
    const stateColor = s => s === 'completed' ? 'var(--success,#22c55e)'
                          : s === 'failed' ? 'var(--danger,#ef4444)'
                          : s === 'running' ? 'var(--primary,#3b82f6)'
                          : '#888';
    let html = '<table><thead><tr>'
      + '<th>Job ID</th><th>Entity Set</th><th>Mode</th><th>State</th>'
      + '<th>Records</th><th>Started</th><th>Completed</th><th>Delta Token</th><th>Logs</th>'
      + '</tr></thead><tbody>';
    for (const r of data.rows) {
      const errMsg = r.error ? String(r.error) : '';
      const errEsc = errMsg.replace(/[<>&"]/g, c => ({'<':'&lt;','>':'&gt;','&':'&amp;','"':'&quot;'}[c]));
      // Show only the brief state label; surface error text via tooltip on hover.
      const stateCell = '<span title="' + errEsc + '" style="color:' + stateColor(r.state) + ';font-weight:600">' + (r.state || '') + '</span>';
      html += '<tr>'
        + '<td>' + r.jobId + '</td>'
        + '<td>' + (r.entitySet || '') + '</td>'
        + '<td>' + (r.mode || '') + '</td>'
        + '<td>' + stateCell + '</td>'
        + '<td style="text-align:right">' + (r.recordCount ?? 0).toLocaleString() + '</td>'
        + '<td>' + (r.startedAt || '') + '</td>'
        + '<td>' + (r.completedAt || '') + '</td>'
        + '<td style="font-family:monospace;font-size:11px;max-width:220px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" title="' + (r.deltaToken ? String(r.deltaToken).replace(/"/g,"&quot;") : "") + '">' + (r.deltaToken ? String(r.deltaToken) : '') + '</td>'
        + '<td><button class="btn-refresh" data-action="view-logs" data-job-id="' + r.jobId + '" data-entity="' + escapeHtml(r.entitySet || '') + '" data-state="' + (r.state || '') + '">View Logs</button></td>'
        + '</tr>';
    }
    html += '</tbody></table>';
    area.innerHTML = html;
  } catch (e) {
    area.innerHTML = '<div class="empty-state" style="color:var(--danger)">Failed to load: ' + e.message + '</div>';
  }
}

async function loadEntities() {
  const tableDiv = document.getElementById('entityTable');
  tableDiv.innerHTML = '<div class="empty-state"><span class="spinner"></span> Loading entity sets...</div>';
  try {
    const res = await fetch('/api/entities');
    const data = await res.json();
    if (data.error) throw new Error(data.error);
    entities = data.entitySets || [];
    renderEntityTable(data.servicePath);
  } catch (e) {
    tableDiv.innerHTML = '<div class="empty-state" style="color:var(--danger)">Failed to load: ' +
      escapeHtml(e.message) + '</div>';
  }
}

function renderEntityTable(servicePath) {
  const tableDiv = document.getElementById('entityTable');
  if (entities.length === 0) {
    tableDiv.innerHTML = '<div class="empty-state">No entity sets found</div>';
    return;
  }
  document.getElementById('entityCount').textContent = entities.length + ' entity sets from ' + servicePath;
  let html = '<table><thead><tr>' +
    '<th class="cb-cell"><input type="checkbox" id="selectAll" onchange="toggleAll(this)"></th>' +
    '<th>Entity Set</th><th>Mode</th><th>Delta Actions</th></tr></thead><tbody>';
  entities.forEach((name, i) => {
    const nameEsc = escapeHtml(name);
    html += '<tr>' +
      '<td class="cb-cell"><input type="checkbox" class="entity-cb" data-name="' + nameEsc + '" onchange="updateBtn()"></td>' +
      '<td>' + nameEsc + '</td>' +
      '<td><select class="mode-select" id="mode_' + i + '"><option value="full">Full</option><option value="full_no_delta">Full (Delta Disabled)</option><option value="delta">Delta</option></select></td>' +
      '<td style="white-space:nowrap">' +
        '<button class="btn-refresh" data-action="delta-status" data-entity="' + nameEsc + '" title="SubscribedTo' + nameEsc + '">Check Status</button> ' +
        '<button class="btn-refresh" style="background:#ef4444;color:#fff" data-action="delta-reset" data-entity="' + nameEsc + '" title="TerminateDeltasFor' + nameEsc + '">Reset Delta</button>' +
      '</td>' +
      '</tr>';
  });
  html += '</tbody></table>';
  tableDiv.innerHTML = html;
  // Wire up per-row delta action buttons via delegation (avoids inline-onclick quoting issues)
  tableDiv.querySelectorAll('button[data-action]').forEach(btn => {
    btn.addEventListener('click', () => {
      const action = btn.dataset.action;
      const entity = btn.dataset.entity;
      if (action === 'delta-status') checkDeltaStatus(entity);
      else if (action === 'delta-reset') resetDelta(entity);
    });
  });
  updateBtn();
}

function toggleAll(master) {
  document.querySelectorAll('.entity-cb').forEach(cb => cb.checked = master.checked);
  updateBtn();
}

function updateBtn() {
  const checked = document.querySelectorAll('.entity-cb:checked').length;
  const btn = document.getElementById('extractBtn');
  btn.disabled = checked === 0;
  btn.textContent = checked > 0 ? '▶ Run Extraction (' + checked + ')' : '▶ Run Extraction';
}

async function runExtraction() {
  const selected = [];
  document.querySelectorAll('.entity-cb:checked').forEach((cb, i) => {
    const name = cb.dataset.name;
    // find the index in the entities array to get the right mode select
    const idx = entities.indexOf(name);
    const modeSelect = document.getElementById('mode_' + idx);
    const mode = modeSelect ? modeSelect.value : 'full';
    selected.push({ entitySet: name, mode: mode });
  });

  if (selected.length === 0) return;

  const format = document.getElementById('formatSelect').value;

  // Group by mode to make separate requests per mode
  const byMode = {};
  selected.forEach(s => {
    if (!byMode[s.mode]) byMode[s.mode] = [];
    byMode[s.mode].push(s.entitySet);
  });

  appendLog('Starting extraction for ' + selected.length + ' entity set(s)...');

  for (const [mode, entitySets] of Object.entries(byMode)) {
    try {
      const res = await fetch('/api/extract', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ entitySets, mode, format })
      });
      const data = await res.json();
      if (data.error) throw new Error(data.error);
      appendLog(data.message);
      activeJobIds.push(...(data.jobIds || []));
    } catch (e) {
      appendLog('ERROR: ' + e.message, 'error');
    }
  }

  startPolling();
}

function startPolling() {
  if (pollTimer) return;
  pollTimer = setInterval(pollJobs, 1500);
  pollJobs();
}

async function pollJobs() {
  try {
    const res = await fetch('/api/jobs');
    const allJobs = await res.json();
    renderJobs(allJobs);

    // Update logs for active jobs
    allJobs.forEach(job => {
      if (job.logs && job.logs.length > 0) {
        const lastLog = job.logs[job.logs.length - 1];
        const key = 'lastlog_' + job.id;
        if (window[key] !== lastLog) {
          window[key] = lastLog;
          const cls = job.state === 'failed' ? 'error' : (job.state === 'completed' ? 'success' : '');
          appendLog('[Job ' + job.id + ' - ' + job.entitySet + '] ' + lastLog, cls);
        }
      }
    });

    // Stop polling if all active jobs are done
    const running = allJobs.filter(j => j.state === 'queued' || j.state === 'running');
    if (running.length === 0 && activeJobIds.length > 0) {
      clearInterval(pollTimer);
      pollTimer = null;
    }
  } catch (e) { /* ignore poll errors */ }
}

function renderJobs(allJobs) {
  const area = document.getElementById('jobsArea');
  if (!allJobs || allJobs.length === 0) {
    area.innerHTML = '<div class="empty-state">No extractions run yet</div>';
    return;
  }
  let html = '<table><thead><tr><th>ID</th><th>Entity Set</th><th>Mode</th><th>Status</th><th>Records</th><th>Output</th></tr></thead><tbody>';
  // Show most recent first
  const sorted = [...allJobs].sort((a, b) => b.id - a.id);
  sorted.forEach(job => {
    const badgeClass = 'badge-' + job.state;
    const statusLabel = job.state === 'running' ? '<span class="spinner"></span>Running' : job.state;
    html += '<tr>' +
      '<td>' + job.id + '</td>' +
      '<td>' + escapeHtml(job.entitySet) + '</td>' +
      '<td>' + job.mode + '</td>' +
      '<td><span class="status-badge ' + badgeClass + '">' + statusLabel + '</span></td>' +
      '<td>' + (job.recordCount || '-') + '</td>' +
      '<td style="font-size:0.8rem;max-width:250px;overflow:hidden;text-overflow:ellipsis">' +
        (job.outputFile ? escapeHtml(job.outputFile) : (job.error ? '<span style="color:var(--danger)">' + escapeHtml(job.error) + '</span>' : '-')) +
      '</td></tr>';
  });
  html += '</tbody></table>';
  area.innerHTML = html;
}

function appendLog(msg, type) {
  // Legacy in-page-only log helper kept for runExtraction flow notifications.
  // Logs sent here are NOT persisted; the structured Job Logs panel pulls from /api/logs.
  console.log('[ui]', msg);
}

let logSinceId = 0;
let logTimer = null;
let logsModalJobId = null;
let logsModalJobState = '';
const LOG_LEVEL_COLORS = { ERROR: 'var(--danger,#ef4444)', WARN: '#d97706', INFO: '#475569', DEBUG: '#94a3b8' };

function openLogsModal(jobId, entitySet, state) {
  logsModalJobId = jobId;
  logsModalJobState = state;
  logSinceId = 0;
  document.getElementById('logsModalTitle').textContent =
    'Job #' + jobId + ' — ' + (entitySet || '') + ' (' + (state || '') + ')';
  document.getElementById('logsModal').style.display = 'flex';
  document.getElementById('logArea').innerHTML = '<div class="empty-state"><span class="spinner"></span> Loading...</div>';
  fetchLogs(true);
  // Auto-poll only while the job is still running
  if (state === 'running' || state === 'queued') {
    if (document.getElementById('logAuto').checked) startLogPolling();
  }
}

function closeLogsModal() {
  document.getElementById('logsModal').style.display = 'none';
  stopLogPolling();
  logsModalJobId = null;
}

function toggleAutoLogs() {
  if (!logsModalJobId) return;
  if (document.getElementById('logAuto').checked &&
      (logsModalJobState === 'running' || logsModalJobState === 'queued')) {
    startLogPolling();
  } else {
    stopLogPolling();
  }
}

function startLogPolling() {
  if (logTimer) return;
  logTimer = setInterval(() => fetchLogs(false), 2000);
}

function stopLogPolling() {
  if (logTimer) { clearInterval(logTimer); logTimer = null; }
}

async function fetchLogs(replace) {
  if (!logsModalJobId) return;
  const params = new URLSearchParams();
  params.set('jobId', String(logsModalJobId));
  if (!replace && logSinceId > 0) params.set('since', String(logSinceId));
  params.set('limit', '500');
  try {
    const res = await fetch('/api/logs?' + params.toString());
    const data = await res.json();
    const area = document.getElementById('logArea');
    if (!data.available) {
      area.innerHTML = '<div class="empty-state" style="color:var(--danger)">Database not reachable — logs unavailable</div>';
      return;
    }
    const rows = data.rows || [];
    if (replace || !document.getElementById('logsTable')) {
      area.innerHTML = '<table id="logsTable"><thead><tr>'
        + '<th style="width:170px">Time</th>'
        + '<th style="width:70px">Level</th>'
        + '<th style="width:90px">Job ID</th>'
        + '<th>Message</th>'
        + '</tr></thead><tbody id="logsBody"></tbody></table>';
    }
    const body = document.getElementById('logsBody');
    if (replace) body.innerHTML = '';
    if (rows.length === 0 && replace) {
      area.innerHTML = '<div class="empty-state">No logs yet</div>';
      return;
    }
    let frag = '';
    for (const r of rows) {
      logSinceId = Math.max(logSinceId, r.id);
      const color = LOG_LEVEL_COLORS[r.level] || '#475569';
      frag += '<tr>'
        + '<td style="font-family:monospace;font-size:12px;color:#666">' + (r.ts || '') + '</td>'
        + '<td style="font-weight:600;color:' + color + '">' + (r.level || 'INFO') + '</td>'
        + '<td style="font-family:monospace">' + r.jobId + '</td>'
        + '<td style="font-family:monospace;font-size:12px">' + escapeHtml(r.message || '') + '</td>'
        + '</tr>';
    }
    if (frag) {
      body.insertAdjacentHTML('beforeend', frag);
      // Cap rows to last 1000 to keep DOM small
      while (body.rows.length > 1000) body.deleteRow(0);
    }
  } catch (e) {
    // ignore transient poll errors
  }
}

function escapeHtml(str) {
  const d = document.createElement('div');
  d.textContent = str;
  return d.innerHTML;
}

// Auto-load entities and history on page open
window.addEventListener('load', () => {
  loadEntities();
  loadHistory();
  // Delegated handler for per-row "View Logs" buttons
  document.getElementById('historyArea').addEventListener('click', (e) => {
    const btn = e.target.closest('button[data-action="view-logs"]');
    if (!btn) return;
    openLogsModal(parseInt(btn.dataset.jobId, 10), btn.dataset.entity, btn.dataset.state);
  });
});
// Close logs modal with Escape
document.addEventListener('keydown', e => { if (e.key === 'Escape') closeLogsModal(); });
</script>
</body>
</html>
""";
}
