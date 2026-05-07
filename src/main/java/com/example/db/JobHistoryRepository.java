package com.example.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Persists job execution history to PostgreSQL.
 * Auto-creates the schema on first use.
 */
public class JobHistoryRepository {

    private static final Logger logger = LoggerFactory.getLogger(JobHistoryRepository.class);

    private final String jdbcUrl;
    private final String dbUser;
    private final String dbPassword;
    private volatile boolean dbAvailable = false;

    public JobHistoryRepository() {
        Properties props = new Properties();
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (is != null) props.load(is);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load application.properties", e);
        }

        this.jdbcUrl = props.getProperty("db.url", "jdbc:postgresql://localhost:5432/extractor_db");
        this.dbUser = props.getProperty("db.user", "postgres");
        this.dbPassword = props.getProperty("db.password", "postgres");
    }

    /**
     * Creates the job_history table if it doesn't exist.
     */
    public void initSchema() {
        String ddl = """
            CREATE TABLE IF NOT EXISTS job_history (
                id              SERIAL PRIMARY KEY,
                job_id          INTEGER NOT NULL,
                entity_set      VARCHAR(255) NOT NULL,
                mode            VARCHAR(10) NOT NULL,
                state           VARCHAR(20) NOT NULL DEFAULT 'queued',
                record_count    INTEGER DEFAULT 0,
                output_file     TEXT,
                error           TEXT,
                delta_token     TEXT,
                started_at      TIMESTAMP NOT NULL DEFAULT NOW(),
                completed_at    TIMESTAMP,
                created_at      TIMESTAMP NOT NULL DEFAULT NOW()
            );
            """;
        // Migration for existing installs that pre-date the delta_token column
        String alter = "ALTER TABLE job_history ADD COLUMN IF NOT EXISTS delta_token TEXT";
        String ddlLogs = """
            CREATE TABLE IF NOT EXISTS job_logs (
                id          BIGSERIAL PRIMARY KEY,
                job_id      INTEGER NOT NULL,
                ts          TIMESTAMP NOT NULL DEFAULT NOW(),
                level       VARCHAR(10) NOT NULL DEFAULT 'INFO',
                message     TEXT NOT NULL
            );
            """;
        String idxLogs = "CREATE INDEX IF NOT EXISTS idx_job_logs_job_id ON job_logs(job_id, id);";

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
            stmt.execute(alter);
            stmt.execute(ddlLogs);
            stmt.execute(idxLogs);
            dbAvailable = true;
            logger.info("Job history + logs tables ready (PostgreSQL: {})", jdbcUrl);
            reconcileOrphanedJobs();
        } catch (SQLException e) {
            dbAvailable = false;
            logger.warn("Job history persistence disabled - cannot reach PostgreSQL ({}): {}",
                    jdbcUrl, e.getMessage());
        }
    }

    /**
     * Marks any leftover 'queued' or 'running' rows from previous runs as 'failed',
     * since the JVM that owned them is no longer alive.
     */
    private void reconcileOrphanedJobs() {
        String sql = "UPDATE job_history SET state = 'failed', " +
                     "error = COALESCE(error, 'Process terminated before completion'), " +
                     "completed_at = NOW() " +
                     "WHERE state IN ('queued', 'running')";
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            int updated = stmt.executeUpdate(sql);
            if (updated > 0) {
                logger.info("Reconciled {} orphaned job(s) from previous runs as 'failed'", updated);
            }
        } catch (SQLException e) {
            logger.warn("Failed to reconcile orphaned jobs: {}", e.getMessage());
        }
    }

    public boolean isAvailable() {
        return dbAvailable;
    }

    /**
     * Inserts a new job record and returns the generated database ID.
     */
    public int insertJob(int jobId, String entitySet, String mode) {
        if (!dbAvailable) return -1;
        String sql = "INSERT INTO job_history (job_id, entity_set, mode, state, started_at) " +
                     "VALUES (?, ?, ?, 'queued', NOW()) RETURNING id";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, jobId);
            ps.setString(2, entitySet);
            ps.setString(3, mode);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int dbId = rs.getInt(1);
                    logger.debug("Inserted job_history id={} for jobId={}, entity={}", dbId, jobId, entitySet);
                    return dbId;
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to insert job history: {}", e.getMessage(), e);
        }
        return -1;
    }

    /**
     * Updates an existing job record with its final state.
     */
    public void updateJob(int jobId, String state, int recordCount, String outputFile, String error) {
        if (!dbAvailable) return;
        String sql = "UPDATE job_history SET state = ?, record_count = ?, output_file = ?, " +
                     "error = ?, completed_at = ? WHERE job_id = ?";

        Timestamp completedAt = ("completed".equals(state) || "failed".equals(state))
                ? Timestamp.valueOf(LocalDateTime.now()) : null;

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, state);
            ps.setInt(2, recordCount);
            ps.setString(3, outputFile);
            ps.setString(4, error);
            ps.setTimestamp(5, completedAt);
            ps.setInt(6, jobId);
            ps.executeUpdate();
            logger.debug("Updated job_history for jobId={}, state={}", jobId, state);
        } catch (SQLException e) {
            logger.error("Failed to update job history: {}", e.getMessage(), e);
        }
    }

    /**
     * Returns all past job records, most recent first.
     */
    public List<Map<String, Object>> getHistory() {
        if (!dbAvailable) return new ArrayList<>();
        String sql = "SELECT job_id, entity_set, mode, state, record_count, " +
                     "output_file, error, delta_token, started_at, completed_at " +
                     "FROM job_history ORDER BY id DESC LIMIT 200";

        List<Map<String, Object>> results = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("jobId", rs.getInt("job_id"));
                row.put("entitySet", rs.getString("entity_set"));
                row.put("mode", rs.getString("mode"));
                row.put("state", rs.getString("state"));
                row.put("recordCount", rs.getInt("record_count"));
                row.put("outputFile", rs.getString("output_file"));
                row.put("error", rs.getString("error"));
                row.put("deltaToken", rs.getString("delta_token"));
                row.put("startedAt", rs.getTimestamp("started_at") != null
                        ? rs.getTimestamp("started_at").toString() : null);
                row.put("completedAt", rs.getTimestamp("completed_at") != null
                        ? rs.getTimestamp("completed_at").toString() : null);
                results.add(row);
            }
        } catch (SQLException e) {
            logger.error("Failed to query job history: {}", e.getMessage(), e);
        }
        return results;
    }

    /**
     * Returns the highest {@code job_id} stored in {@code job_history}, or {@code 0} when empty
     * (or when the database is not available). Used by the web UI to seed its in-memory counter
     * so that newly created job IDs stay strictly greater than any previously persisted ID.
     */
    public int getMaxJobId() {
        if (!dbAvailable) return 0;
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COALESCE(MAX(job_id), 0) FROM job_history")) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            logger.warn("Failed to read MAX(job_id): {}", e.getMessage());
        }
        return 0;
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, dbUser, dbPassword);
    }

    // ── Delta token persistence ─────────────────────────────────

    /** Stores the delta token returned by SAP for the given job's run. */
    public void updateDeltaToken(int jobId, String deltaToken) {
        if (!dbAvailable) return;
        String sql = "UPDATE job_history SET delta_token = ? WHERE job_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, deltaToken);
            ps.setInt(2, jobId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warn("Failed to update delta token for jobId={}: {}", jobId, e.getMessage());
        }
    }

    /**
     * Returns the most recent non-null delta token for the given entity set,
     * preferring rows from completed runs. {@code null} when no token has been stored.
     */
    public String getLatestDeltaToken(String entitySet) {
        if (!dbAvailable) return null;
        String sql = "SELECT delta_token FROM job_history " +
                     "WHERE entity_set = ? AND delta_token IS NOT NULL AND delta_token <> '' " +
                     "ORDER BY (state = 'completed') DESC, id DESC LIMIT 1";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entitySet);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        } catch (SQLException e) {
            logger.warn("Failed to read latest delta token for {}: {}", entitySet, e.getMessage());
        }
        return null;
    }

    // ── Structured job logs ───────────────────────────────────

    /** Append a single structured log line for a job. Silently no-ops if DB is down. */
    public void appendLog(int jobId, String level, String message) {
        if (!dbAvailable) return;
        String sql = "INSERT INTO job_logs (job_id, level, message) VALUES (?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, jobId);
            ps.setString(2, level == null ? "INFO" : level);
            ps.setString(3, message == null ? "" : message);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.debug("Failed to persist job log for jobId={}: {}", jobId, e.getMessage());
        }
    }

    /**
     * Returns log entries. If jobId &lt; 0 returns recent entries across all jobs.
     * If sinceId &gt; 0, returns only entries with id &gt; sinceId (incremental polling).
     */
    public List<Map<String, Object>> getLogs(int jobId, long sinceId, int limit) {
        if (!dbAvailable) return new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT id, job_id, ts, level, message FROM job_logs WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (jobId >= 0) { sql.append(" AND job_id = ?"); params.add(jobId); }
        if (sinceId > 0) { sql.append(" AND id > ?"); params.add(sinceId); }
        sql.append(" ORDER BY id ASC LIMIT ?");
        params.add(limit > 0 ? limit : 500);

        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("jobId", rs.getInt("job_id"));
                    Timestamp ts = rs.getTimestamp("ts");
                    row.put("ts", ts != null ? ts.toString() : null);
                    row.put("level", rs.getString("level"));
                    row.put("message", rs.getString("message"));
                    out.add(row);
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to query job logs: {}", e.getMessage(), e);
        }
        return out;
    }
}
