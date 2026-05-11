package com.example.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Stores S/4HANA service definitions (name, path, optional per-service credentials)
 * in PostgreSQL. Falls back to in-memory empty state if the database is unreachable.
 */
public class ServicesRepository {

    private static final Logger logger = LoggerFactory.getLogger(ServicesRepository.class);

    private final String jdbcUrl;
    private final String dbUser;
    private final String dbPassword;
    private volatile boolean dbAvailable = false;

    public ServicesRepository() {
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

    public void initSchema() {
        String ddl = """
            CREATE TABLE IF NOT EXISTS services (
                id              SERIAL PRIMARY KEY,
                name            VARCHAR(255) NOT NULL,
                service_path    TEXT NOT NULL UNIQUE,
                base_url        TEXT,
                username        VARCHAR(255),
                password        TEXT,
                sap_client      VARCHAR(10),
                prefer_header   TEXT,
                is_default      BOOLEAN NOT NULL DEFAULT FALSE,
                is_deleted      BOOLEAN NOT NULL DEFAULT FALSE,
                deleted_at      TIMESTAMP,
                created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
                updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
            );
            """;
        // Migrations for installs that pre-date the soft-delete columns
        String alter1 = "ALTER TABLE services ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT FALSE";
        String alter2 = "ALTER TABLE services ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP";
        // Replace the column-level UNIQUE with a partial unique index so re-creating a soft-deleted
        // path is allowed. The auto-generated constraint name from PG is "<table>_<column>_key".
        String dropUnique = "ALTER TABLE services DROP CONSTRAINT IF EXISTS services_service_path_key";
        String partialIdx = "CREATE UNIQUE INDEX IF NOT EXISTS ux_services_service_path_active " +
                            "ON services(service_path) WHERE is_deleted = FALSE";
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
            stmt.execute(alter1);
            stmt.execute(alter2);
            stmt.execute(dropUnique);
            stmt.execute(partialIdx);
            dbAvailable = true;
            logger.info("Services table ready (PostgreSQL: {})", jdbcUrl);
        } catch (SQLException e) {
            dbAvailable = false;
            logger.warn("Services persistence disabled - cannot reach PostgreSQL ({}): {}",
                    jdbcUrl, e.getMessage());
        }
    }

    public boolean isAvailable() { return dbAvailable; }

    /**
     * Seeds the table from application.properties values if it is currently empty.
     * Uses s4hana.service.paths (comma-separated) and s4hana.service.path as the default.
     */
    public void seedFromPropertiesIfEmpty(String defaultPath, List<String> allPaths,
                                          String baseUrl, String user, String password,
                                          String client, String preferHeader) {
        if (!dbAvailable) return;
        try (Connection conn = getConnection();
             PreparedStatement check = conn.prepareStatement("SELECT COUNT(*) FROM services")) {
            ResultSet rs = check.executeQuery();
            rs.next();
            if (rs.getInt(1) > 0) return;
            logger.info("Seeding services table from application.properties ({} entries)", allPaths.size());
            String sql = "INSERT INTO services (name, service_path, base_url, username, password, sap_client, prefer_header, is_default) " +
                         "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (String path : allPaths) {
                    boolean isDefault = path.equals(defaultPath);
                    String name = deriveName(path);
                    ps.setString(1, name);
                    ps.setString(2, path);
                    ps.setString(3, baseUrl);
                    ps.setString(4, user);
                    ps.setString(5, password);
                    ps.setString(6, client);
                    ps.setString(7, preferHeader);
                    ps.setBoolean(8, isDefault);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        } catch (SQLException e) {
            logger.warn("Failed to seed services table: {}", e.getMessage());
        }
    }

    private static String deriveName(String path) {
        if (path == null || path.isBlank()) return "(unnamed)";
        String[] parts = path.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (!parts[i].isBlank() && !parts[i].matches("\\d+")) return parts[i];
        }
        return path;
    }

    public List<Map<String, Object>> listAll() {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!dbAvailable) return out;
        String sql = "SELECT id, name, service_path, base_url, username, password, sap_client, prefer_header, " +
                     "is_default, created_at, updated_at FROM services WHERE is_deleted = FALSE " +
                     "ORDER BY is_default DESC, id ASC";
        try (Connection conn = getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.add(toMap(rs));
        } catch (SQLException e) {
            logger.warn("Failed to list services: {}", e.getMessage());
        }
        return out;
    }

    public Map<String, Object> findByPath(String servicePath) {
        if (!dbAvailable || servicePath == null || servicePath.isBlank()) return null;
        String sql = "SELECT id, name, service_path, base_url, username, password, sap_client, prefer_header, " +
                     "is_default, created_at, updated_at FROM services WHERE service_path = ? AND is_deleted = FALSE";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, servicePath);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return toMap(rs);
            }
        } catch (SQLException e) {
            logger.warn("Failed to find service: {}", e.getMessage());
        }
        return null;
    }

    public Map<String, Object> findDefault() {
        if (!dbAvailable) return null;
        String sql = "SELECT id, name, service_path, base_url, username, password, sap_client, prefer_header, " +
                     "is_default, created_at, updated_at FROM services WHERE is_default = TRUE AND is_deleted = FALSE LIMIT 1";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return toMap(rs);
        } catch (SQLException e) {
            logger.warn("Failed to find default service: {}", e.getMessage());
        }
        return null;
    }

    public Map<String, Object> insert(String name, String servicePath, String baseUrl,
                                      String user, String password, String client,
                                      String preferHeader, boolean isDefault) {
        if (!dbAvailable) return null;
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (isDefault) clearDefault(conn);
                String sql = "INSERT INTO services (name, service_path, base_url, username, password, sap_client, prefer_header, is_default) " +
                             "VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id";
                int id;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, name);
                    ps.setString(2, servicePath);
                    ps.setString(3, nullIfBlank(baseUrl));
                    ps.setString(4, nullIfBlank(user));
                    ps.setString(5, nullIfBlank(password));
                    ps.setString(6, nullIfBlank(client));
                    ps.setString(7, nullIfBlank(preferHeader));
                    ps.setBoolean(8, isDefault);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        id = rs.getInt(1);
                    }
                }
                conn.commit();
                return findById(id);
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            }
        } catch (SQLException e) {
            logger.warn("Failed to insert service: {}", e.getMessage());
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public Map<String, Object> update(int id, String name, String servicePath, String baseUrl,
                                      String user, String password, String client,
                                      String preferHeader, boolean isDefault) {
        if (!dbAvailable) return null;
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (isDefault) clearDefault(conn);
                String sql = "UPDATE services SET name=?, service_path=?, base_url=?, username=?, password=?, " +
                             "sap_client=?, prefer_header=?, is_default=?, updated_at=NOW() WHERE id=?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, name);
                    ps.setString(2, servicePath);
                    ps.setString(3, nullIfBlank(baseUrl));
                    ps.setString(4, nullIfBlank(user));
                    ps.setString(5, nullIfBlank(password));
                    ps.setString(6, nullIfBlank(client));
                    ps.setString(7, nullIfBlank(preferHeader));
                    ps.setBoolean(8, isDefault);
                    ps.setInt(9, id);
                    ps.executeUpdate();
                }
                conn.commit();
                return findById(id);
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            }
        } catch (SQLException e) {
            logger.warn("Failed to update service: {}", e.getMessage());
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public boolean delete(int id) {
        if (!dbAvailable) return false;
        // Soft delete: keep the row, mark it deleted, clear default flag.
        String sql = "UPDATE services SET is_deleted = TRUE, is_default = FALSE, deleted_at = NOW(), updated_at = NOW() " +
                     "WHERE id = ? AND is_deleted = FALSE";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.warn("Failed to soft-delete service: {}", e.getMessage());
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public Map<String, Object> findById(int id) {
        if (!dbAvailable) return null;
        String sql = "SELECT id, name, service_path, base_url, username, password, sap_client, prefer_header, " +
                     "is_default, created_at, updated_at FROM services WHERE id = ? AND is_deleted = FALSE";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return toMap(rs);
            }
        } catch (SQLException e) {
            logger.warn("Failed to find service by id: {}", e.getMessage());
        }
        return null;
    }

    private void clearDefault(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("UPDATE services SET is_default = FALSE WHERE is_default = TRUE");
        }
    }

    private static String nullIfBlank(String s) { return (s == null || s.isBlank()) ? null : s; }

    private static Map<String, Object> toMap(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getInt("id"));
        m.put("name", rs.getString("name"));
        m.put("servicePath", rs.getString("service_path"));
        m.put("baseUrl", rs.getString("base_url"));
        m.put("username", rs.getString("username"));
        // password intentionally omitted from this map for safety; callers needing it use findByPathWithSecret
        m.put("sapClient", rs.getString("sap_client"));
        m.put("preferHeader", rs.getString("prefer_header"));
        m.put("isDefault", rs.getBoolean("is_default"));
        Timestamp c = rs.getTimestamp("created_at");
        Timestamp u = rs.getTimestamp("updated_at");
        m.put("createdAt", c == null ? null : c.toString());
        m.put("updatedAt", u == null ? null : u.toString());
        return m;
    }

    /**
     * Returns the row including the password column (for use by the extraction engine only).
     */
    public Map<String, Object> findByPathWithSecret(String servicePath) {
        if (!dbAvailable || servicePath == null || servicePath.isBlank()) return null;
        String sql = "SELECT id, name, service_path, base_url, username, password, sap_client, prefer_header, " +
                     "is_default FROM services WHERE service_path = ? AND is_deleted = FALSE";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, servicePath);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getInt("id"));
                    m.put("name", rs.getString("name"));
                    m.put("servicePath", rs.getString("service_path"));
                    m.put("baseUrl", rs.getString("base_url"));
                    m.put("username", rs.getString("username"));
                    m.put("password", rs.getString("password"));
                    m.put("sapClient", rs.getString("sap_client"));
                    m.put("preferHeader", rs.getString("prefer_header"));
                    m.put("isDefault", rs.getBoolean("is_default"));
                    return m;
                }
            }
        } catch (SQLException e) {
            logger.warn("Failed to find service with secret: {}", e.getMessage());
        }
        return null;
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, dbUser, dbPassword);
    }
}
