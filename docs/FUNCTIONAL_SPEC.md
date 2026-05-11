# S/4HANA Generic Extractor — Functional Specification

**Version:** 1.0
**Date:** May 2026
**Status:** Implemented

---

## 1. Purpose

A generic, configuration-driven extractor that pulls business data from any
SAP S/4HANA OData v2 service exposed via the SAP Gateway and writes it to local
files (CSV or JSON). The application discovers entity sets and fields
dynamically from the service `$metadata` document — nothing is hard-coded per
entity.

It runs in two modes:

1. **CLI** — single-shot extraction driven by `application.properties` and
   command-line arguments.
2. **Web UI** — embedded HTTP server (port 8080 by default) that lets a user
   browse entity sets, choose extraction strategy, trigger extractions, monitor
   progress, view structured logs, and review historical job outcomes.

---

## 2. Scope

### In Scope
- OData v2 over HTTP basic authentication, `sap-client` parameter
- Dynamic discovery of services, entity sets, and entity-type fields
- Three extraction strategies: **Full**, **Delta**, **Full without Delta** (parallel)
- Output to CSV or JSON
- Persistent job history and structured per-job logs in PostgreSQL
- Concurrent job tracking with cancel/conflict handling
- Delta subscription management via SAP function imports
  (`SubscribedTo<EntitySet>`, `TerminateDeltasFor<EntitySet>`)

### Out of Scope
- OData v4
- Authentication other than HTTP Basic
- Cloud / multi-tenant deployment
- Automatic upload of extracted files to downstream systems
- Resumable/checkpointed extractions across server restarts

---

## 3. Actors

| Actor | Description |
|-------|-------------|
| **Operator (UI user)** | Accesses the Web UI on `http://localhost:8080`, picks an entity, and triggers / monitors extractions. |
| **CLI user** | Runs the JAR/Maven goal for unattended extraction (e.g. nightly scheduler). |
| **SAP S/4HANA system** | Exposes the OData v2 service. Source of all data and delta tokens. |
| **PostgreSQL** | Persists `job_history` and `job_logs`. |

---

## 4. System Context

```
+----------------+        OData v2 / HTTPS        +-----------------+
| Operator (UI)  | <------------------------------|  S/4HANA OData  |
| Browser        |        $metadata, $top/$skip   |  ZNC_KX_SO_SRV  |
+--------+-------+        Prefer headers, delta   +--------+--------+
         |  HTTP                                            ^
         v                                                  |
+-----------------+    JDBC     +----------------+          |
|  Web UI Server  |<----------->|  PostgreSQL    |          |
|  (Java, port    |             |  job_history   |          |
|   8080)         |             |  job_logs      |          |
|                 |---------------------------------------- + (HTTP)
|  CLI mode       |                                          
+-----------------+
         |
         v
   output/*.csv | *.json
```

---

## 5. Functional Requirements

### 5.1 Configuration (FR-CFG)
| ID | Requirement |
|----|-------------|
| FR-CFG-1 | All connection settings (URL, user, password, client, service path, default entity set, parallel calls, prefer header) shall be loaded from `application.properties`. |
| FR-CFG-2 | DB settings (`db.url`, `db.user`, `db.password`) shall be loaded from the same file; if the DB is unreachable, the application shall continue to operate without persisting history or logs. |
| FR-CFG-3 | Per-job overrides for entity set, mode, parallel workers, and `$top` page size shall be supplied per request and shall take precedence over `application.properties`. |

### 5.2 Service & Metadata Discovery (FR-DSC)
| ID | Requirement |
|----|-------------|
| FR-DSC-1 | The system shall list all OData services published by the SAP Gateway catalog and surface them in the UI. |
| FR-DSC-2 | For a chosen service, the system shall enumerate all entity sets exposed by `$metadata`. |
| FR-DSC-3 | For the selected entity set, the system shall introspect the underlying entity type and use **all primitive fields** as the `$select` projection. |
| FR-DSC-4 | Discovery results shall be re-fetched on demand via the **Refresh** action; results shall be cached only within the request. |

### 5.3 Extraction (FR-EXT)
| ID | Requirement |
|----|-------------|
| FR-EXT-1 | The system shall support three modes per extraction: **`full`**, **`delta`**, **`full_no_delta`**. |
| FR-EXT-2 | **Full** mode shall use server-driven paging: send `Prefer: odata.maxpagesize=N,odata.track-changes`, follow `__next` links, and persist the trailing `!deltatoken=...` to `job_history.delta_token`. |
| FR-EXT-3 | **Delta** mode shall read the latest stored `delta_token` for the entity set and request `?!deltatoken=<token>`; only changed records are returned. |
| FR-EXT-4 | **Full without Delta** mode shall NOT use the `Prefer` header. It shall split the dataset across N parallel workers using `$top` + `$skip` (worker `i` issues offsets `i*top, (i+N)*top, ...`) until each worker receives an empty page. |
| FR-EXT-5 | In Full-without-Delta + CSV mode, each worker shall write a part-file (`<prefix>_part<i>.csv`); after all workers finish, parts shall be merged into a single CSV file (one header row preserved) and the part files deleted. |
| FR-EXT-6 | The total record count returned to the UI shall be the sum across all workers. |
| FR-EXT-7 | The user shall be able to override `parallelCalls` (workers, default 5) and `pageSize` (`$top`, default 5000) per extraction; both inputs shall be enabled in the UI **only** when the row's mode is Full without Delta. |

### 5.4 Output (FR-OUT)
| ID | Requirement |
|----|-------------|
| FR-OUT-1 | All output files shall be written to the `output/` directory at the working directory root. |
| FR-OUT-2 | Filename pattern: `<entityset_lowercase>_<full|delta|full_no_delta>_load_<yyyyMMdd_HHmmss>.<csv|json>`. |
| FR-OUT-3 | Delta-link records (server-emitted `__delta` rows with `id` only) for `full` mode shall be saved to a separate `deltalinksof<entityset>...csv` for traceability. |
| FR-OUT-4 | CSV files shall use comma separator, header row from the discovered field list, and UTF-8 encoding. JSON output shall be a single JSON array of objects (key = field name, value = string). |

### 5.5 Job Lifecycle (FR-JOB)
| ID | Requirement |
|----|-------------|
| FR-JOB-1 | Each extraction shall be assigned a unique numeric `job_id`. The counter shall be seeded from `MAX(job_id)` in `job_history` on startup so IDs do not duplicate across restarts. |
| FR-JOB-2 | Job state machine: `queued → running → completed`, with terminal states `failed` and `cancelled`. |
| FR-JOB-3 | On submit, an `INSERT INTO job_history (state='queued')` and an initial `job_logs` entry "Job queued for ... extraction of ..." shall be written. |
| FR-JOB-4 | When the worker thread starts, the row shall be updated to `state='running'`. |
| FR-JOB-5 | On terminal state, `job_history` shall be updated with `state`, `record_count`, `output_file`, `error`, and `completed_at`. |
| FR-JOB-6 | If a new request targets an entity set with a job already in `queued` or `running` state, the API shall return **HTTP 409** with payload `{conflict:true, entitySets, jobIds, message}`; the UI shall prompt the user, and on confirmation re-POST the request with `force:true`. With `force:true`, the existing job(s) shall be cancelled (`Thread.interrupt()` + `state='cancelled'`) before the new job starts. |
| FR-JOB-7 | On startup, any rows still in `queued`/`running` state shall be marked `failed` with error `"Process terminated before completion"` (orphan recovery). |

### 5.6 Web UI (FR-UI)
| ID | Requirement |
|----|-------------|
| FR-UI-1 | The UI shall be served on port 8080 (configurable via CLI arg) by an embedded `com.sun.net.httpserver.HttpServer`; no external web server is required. |
| FR-UI-2 | The Entity Sets card shall list one row per entity, each with: name, **Mode** dropdown (default `Full (Delta Disabled)`, then `Full`, then `Delta`), and an **Action** column containing **▶ Run Extraction**, **Check Status**, and **Reset Delta** buttons. |
| FR-UI-3 | The toolbar shall expose: **Output Format** (CSV/JSON), **Parallel** (1–32), **$top** (1–100000, default 5000), and a **Refresh** button. The Parallel and $top inputs shall be disabled unless at least one row's mode is Full without Delta. |
| FR-UI-4 | Clicking **▶ Run Extraction** on a row shall submit a single-entity extraction with the row's selected mode. |
| FR-UI-5 | The Job History card shall poll `/api/history` every 30 seconds and display columns: Job ID, Entity Set, Mode, State, Records, Started, Completed, **Duration**, Delta Token, Logs. |
| FR-UI-6 | Duration shall be formatted as `M:SS` or `H:MM:SS`; for running/queued jobs, the elapsed time shall be computed from `started_at` to `now()`. |
| FR-UI-7 | The Logs button on each row shall open a modal that fetches `/api/logs?jobId=&since=&limit=` and shall auto-refresh every 30s while the job is in `queued` or `running` state. Log entries shall be color-coded by level (ERROR, WARN, INFO, DEBUG). |
| FR-UI-8 | The **Check Status** button shall invoke the `SubscribedTo<EntitySet>` function import; **Reset Delta** shall invoke `TerminateDeltasFor<EntitySet>` (with confirmation). |

### 5.7 Logging & Observability (FR-LOG)
| ID | Requirement |
|----|-------------|
| FR-LOG-1 | Every business event (queued, started, fetched page N at skip=X, merge complete, completed, failed, cancelled) shall be persisted to `job_logs(job_id, ts, level, message)`. |
| FR-LOG-2 | Each outbound OData URL shall be logged at INFO level so an operator can replay/debug requests. |
| FR-LOG-3 | The same events shall also be emitted via SLF4J to the Maven console for operators running from a terminal. |

### 5.8 REST API (FR-API)
| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/` | GET | Serves the embedded `INDEX_HTML` UI. |
| `/api/services` | GET | List OData services from the SAP catalog. |
| `/api/entities?service=<path>` | GET | List entity sets for a service. |
| `/api/extract` | POST | Body `{entitySets:[...], mode, format, parallelCalls, pageSize, force?}`. Returns `{jobIds, message}`; **409** `{conflict:true,...}` on entity-set conflict. |
| `/api/jobs` | GET | In-memory snapshot of all jobs in the current process. |
| `/api/jobs?id=<n>` | GET | Single job snapshot. |
| `/api/history` | GET | Persisted history rows from PostgreSQL `job_history`. |
| `/api/logs?jobId=&since=&limit=` | GET | Structured log rows from `job_logs`, ordered by `id`. |
| `/api/delta/status?entitySet=<name>` | GET | Invoke `SubscribedTo<EntitySet>`. |
| `/api/delta/reset?entitySet=<name>` | POST | Invoke `TerminateDeltasFor<EntitySet>`. |

All responses are JSON. Error responses use HTTP 4xx/5xx with body `{"error":"..."}`.

---

## 6. Data Model (PostgreSQL)

### `job_history`
| Column | Type | Notes |
|--------|------|-------|
| id | serial PK | |
| job_id | int NOT NULL | application-assigned, unique across restarts |
| entity_set | varchar(255) NOT NULL | |
| mode | varchar(32) NOT NULL | `full`, `delta`, `full_no_delta` |
| state | varchar(20) NOT NULL DEFAULT 'queued' | |
| record_count | int DEFAULT 0 | |
| output_file | text | absolute path |
| error | text | |
| started_at | timestamp NOT NULL DEFAULT now() | |
| completed_at | timestamp | NULL for in-flight jobs |
| created_at | timestamp NOT NULL DEFAULT now() | |
| delta_token | text | most recent delta token captured during a `full` run |

### `job_logs`
| Column | Type | Notes |
|--------|------|-------|
| id | bigserial PK | monotonic; UI uses for `since` cursor |
| job_id | int NOT NULL | FK-by-convention to `job_history.job_id` |
| ts | timestamp NOT NULL DEFAULT now() | |
| level | varchar(10) NOT NULL | INFO/WARN/ERROR/DEBUG |
| message | text NOT NULL | |

---

## 7. Non-Functional Requirements

| ID | Requirement |
|----|-------------|
| NFR-1 | Throughput: Full without Delta with 5 workers @ `$top=5000` shall sustain 50k–80k rows/min against a healthy SAP system (network-bound). |
| NFR-2 | The UI shall remain responsive while extractions are running; long-running tasks must execute on background threads. |
| NFR-3 | The UI shall poll background state at a 30 s cadence to minimize browser/network load. |
| NFR-4 | The application shall start with no DB available and degrade gracefully (no history/log persistence, no orphan recovery). |
| NFR-5 | All credentials are stored in `application.properties` only; the file shall not be committed to source control with real secrets in a public repo (currently committed in a private repo). |
| NFR-6 | Output files shall be written incrementally in CSV mode (single-pass) and via worker part-files in parallel mode, so memory usage is independent of dataset size. |

---

## 8. Operational Procedures

### 8.1 Starting the Web UI
```powershell
mvn -q clean compile
mvn exec:java "-Dexec.mainClass=com.example.GenericExtractorApp" "-Dexec.args=--web"
# Open http://localhost:8080
```

### 8.2 Running a CLI extraction
```powershell
mvn exec:java "-Dexec.mainClass=com.example.GenericExtractorApp" "-Dexec.args=output csv"
```

### 8.3 Stopping a stuck server
```powershell
(Get-NetTCPConnection -LocalPort 8080 -State Listen).OwningProcess |
  Sort-Object -Unique | ForEach-Object { Stop-Process -Id $_ -Force }
```

### 8.4 Resetting delta subscription
- Use the **Reset Delta** button on the entity row, OR
- Call `POST /api/delta/reset?entitySet=<name>`.

---

## 9. Constraints & Assumptions

- SAP S/4HANA system at `http://10.250.0.20:50000`, OData v2 service
  `ZNC_KX_SO_SRV`, default entity set `FactsOfZC_KX_SALESORD` (48 fields).
- PostgreSQL 14+ at `localhost:5432`, database `extractor_db`, user
  `postgres`/`postgres`.
- Java 17+, Maven 3.8+.
- Single-host deployment; no horizontal scaling.

---

## 10. Known Limitations / Future Work

1. **No checkpointing**: a server restart kills in-flight jobs; they are
   recovered as `failed` with message *"Process terminated before completion"*.
   A future enhancement could persist worker offsets and resume.
2. **No authentication on the Web UI** — assumes localhost-only access.
3. **CSV merger** concatenates byte-wise after the first header line; assumes
   all part files share the same schema (true by construction).
4. **OData v4 not supported.**
5. **Delta token is stored only on the most recent successful `full` run** —
   manual coordination is needed if multiple operators run extractions in
   parallel for the same entity (the conflict guard prevents this within one
   server instance but not across instances).

---

## 11. Traceability — User-Stated Requirements → Implementation

| User Requirement (chronological) | Where Implemented |
|----------------------------------|-------------------|
| Generic OData v2 extractor (no hard-coded entity) | `MetadataDiscovery`, `ODataExtractor` |
| Full + Delta with `Prefer: odata.track-changes` | `ODataExtractor.extract()` |
| Persist delta token in DB | `job_history.delta_token`, `JobHistoryRepository.updateJob` |
| Web UI on port 8080 | `WebUIServer` |
| List services & entity sets in UI | `/api/services`, `/api/entities` |
| Run multiple entities at once (legacy) → per-row Run | `renderEntityTable()` + `runExtractionFor()` |
| `Full without Delta` mode with parallel workers | `ODataExtractor.extractParallelToCsvFiles()` + `runWorker()` |
| Per-worker CSV part files merged into one | `CsvFileMerger.merge()` |
| `$top` field in UI (default 5000) | `pageSizeInput` + `ExtractorConfig.pageSizeOverride` |
| `Full (Delta Disabled)` first option in dropdown | `renderEntityTable()` |
| Parallel input enabled only when mode = Full without Delta | `updateBtn()` |
| Job History with **Duration** column | `loadHistory()` + `formatDuration()` |
| Conflict warning + cancel running job (409 + force) | `handleExtract()` + `cancelJob()` |
| Removed "Live Extraction Jobs" card | UI cleanup |
| Persist URLs to `job_logs` | `ODataExtractor.jobLog()` |
| README + push to GitHub | `README.md`, repo `amitkusi85/s4hana-generic-extractor` |
| Rename "Delta Actions" → "Action"; per-row Run button | `renderEntityTable()` |
| Show queued state in logs | `handleExtract()` writes initial `Job queued ...` log + `repo.updateJob('running')` when worker starts |
| Polling cadence reduced to 30 s | `startPolling()`, `startLogPolling()` |
| `mode` column too short (varchar(10)) | `ALTER TABLE job_history ALTER COLUMN mode TYPE varchar(32)` |

---
*End of specification.*
