# S/4HANA Generic OData Extractor

A configurable, schema-driven Java application that extracts data from any SAP S/4HANA OData service into CSV or JSON, with a built-in web UI, persistent job history, structured per-job logs, and delta-token-based incremental loads.

Nothing about the entity, fields, or service path is hardcoded — point it at any OData v2 service in S/4HANA and it will discover the metadata and extract the data.

## Features

- **Generic / metadata-driven** — fields are auto-discovered from the OData `$metadata` document; no model classes to write.
- **Three extraction modes**:
  - `full` — full load with delta token captured for next run
  - `delta` — incremental load using the previously stored delta token
  - `full_no_delta` — parallelized full load (no delta tracking) using `$skip`/`$top` pagination
- **Multiple output formats** — CSV (streamed) or JSON.
- **Web UI** (port 8080 by default) for browsing entity sets, kicking off extractions, and inspecting job history + live logs.
- **Persistent job history** in PostgreSQL — `job_history` and `job_logs` tables, with strictly increasing job IDs across restarts.
- **Delta token persistence** — stored in PostgreSQL (`job_history.delta_token`), with a file-based fallback if the DB is unavailable.

## Project Layout

```
pom.xml
src/main/java/com/example/
  GenericExtractorApp.java        Entry point (CLI + Web UI launcher)
  WebUIServer.java                JDK HttpServer-based web UI + REST API
  config/ExtractorConfig.java     Loads application.properties
  db/JobHistoryRepository.java    PostgreSQL persistence (jobs + logs + delta tokens)
  extractor/
    HttpClient.java               HTTP client with SAP basic-auth + sap-client header
    MetadataDiscovery.java        Parses $metadata to discover field names
    ServiceDiscovery.java         Lists available entity sets in a service
    ODataExtractor.java           Paginated extraction with delta token support
  writer/
    CsvFileWriter.java            Streamed CSV writer (opencsv)
    JsonFileWriter.java           JSON writer (Gson)
src/main/resources/application.properties
output/                           Generated CSV/JSON files (gitignored)
```

## Prerequisites

- **Java 17+**
- **Maven 3.8+**
- **PostgreSQL** running locally (or update `db.*` properties). The app auto-creates the schema on first run.
- Network access to your S/4HANA OData endpoint.

## Configuration

Edit [src/main/resources/application.properties](src/main/resources/application.properties):

```properties
# Server connection
s4hana.url=http://10.250.0.20:50000
s4hana.user=AMITKUSI
s4hana.password=Welcome123456
s4hana.client=100

# OData service
s4hana.service.path=/sap/opu/odata/sap/ZNC_KX_SO_SRV
s4hana.entity.set=FactsOfZC_KX_SALESORD

# Extraction mode: full | delta | full_no_delta
s4hana.extraction.mode=full

# HTTP preferences
s4hana.prefer=odata.maxpagesize=5000,odata.track-changes

# Parallel calls to SAP (used in full_no_delta mode)
s4hana.parallel.calls=5

# PostgreSQL database for job history
db.url=jdbc:postgresql://localhost:5432/extractor_db
db.user=postgres
db.password=postgres
```

> Make sure the `extractor_db` database exists in PostgreSQL before running. The application creates the tables automatically.

## Running

### CLI extraction

```powershell
mvn compile exec:java
# Or with custom output directory and format:
mvn compile exec:java "-Dexec.args=output json"
```

Outputs go to `output/<entityset>_<mode>_load_<timestamp>.<csv|json>`.

### Web UI

```powershell
mvn compile exec:java "-Dexec.args=--web"
# Custom port:
mvn compile exec:java "-Dexec.args=--web 9090"
```

Then open http://localhost:8080.

The UI provides:
- A list of entity sets discovered from the configured OData service
- A button to start extractions in any of the three modes
- A live job history table with state (queued / running / completed / failed) and per-job **View Logs** modal that auto-refreshes while the job is running
- A delta-token status panel with a reset button

### REST API endpoints (web mode)

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/entities` | List entity sets in the configured service |
| POST | `/api/extract` | Start an extraction (body: `{ entitySet, mode, format }`) |
| GET | `/api/jobs` | All in-memory jobs from this process |
| GET | `/api/history` | Persisted job history from PostgreSQL |
| GET | `/api/logs?jobId=N&since=M&limit=500` | Structured logs for a specific job |
| GET | `/api/delta/status` | Latest delta token info |
| POST | `/api/delta/reset` | Clear the stored delta token |

## Database Schema

Tables are created automatically on first run:

- `job_history(job_id, entity_set, mode, state, record_count, output_file, error_message, delta_token, started_at, finished_at)`
- `job_logs(id, job_id, ts, level, message)`

Job IDs are seeded from `MAX(job_id)` on startup so they remain strictly increasing across restarts.

## Build a Runnable JAR

```powershell
mvn clean package
java -jar target/s4hana-generic-extractor-1.0-SNAPSHOT.jar --web
```

## Notes

- The `output/` and `target/` directories are gitignored — extracted data files can be very large and are regenerated by re-running the app.
- Credentials in `application.properties` are committed for development convenience; replace them or externalize them before deploying anywhere shared.
