package com.pixelflow.server.tools;

import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;

/** Offline import into an empty MySQL database. No Spring startup or demo seeding. */
public final class MigrateH2ToMysql {
    private static final List<String> TABLES = List.of("users", "sessions", "posts", "interactions",
            "behavior_events", "moderation_records", "user_status_audit", "media", "post_media",
            "seed_batches", "event_export_state", "event_export_audit", "recommendation_preferences",
            "recommendation_blocked_authors", "recommendation_suppressed_topics");

    public static void main(String[] args) throws Exception {
        try {
            migrate(required("SOURCE_DB_URL"), required("SOURCE_DB_USER"), required("SOURCE_DB_PASSWORD"),
                    required("DB_URL"), required("DB_USER"), required("DB_PASSWORD"));
        } catch (Exception error) {
            // Avoid logging URLs, passwords or user data in driver messages.
            System.err.println("Migration failed (" + error.getClass().getSimpleName()
                    + "). Check offline H2 source, schema version V6 and empty MySQL target. Source unchanged.");
            System.exit(1);
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }

    public static void migrate(String sourceUrl, String sourceUser, String sourcePassword,
                               String targetUrl, String targetUser, String targetPassword) throws Exception {
        if (!sourceUrl.startsWith("jdbc:h2:file:") || !targetUrl.startsWith("jdbc:mysql:"))
            throw new IllegalArgumentException("Expected H2 file source and MySQL target");
        try (Connection src = DriverManager.getConnection(sourceUrl + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r", sourceUser, sourcePassword)) {
            // Refuse silently dropping fields/tables from an unknown source version.
            try (var st = src.createStatement(); var rs = st.executeQuery(
                    "SELECT version FROM flyway_schema_history WHERE success=TRUE AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")) {
                if (!rs.next() || !"6".equals(rs.getString(1))) throw new IllegalStateException("Expected source V6");
            }
            Flyway.configure().dataSource(targetUrl, targetUser, targetPassword)
                    .locations("classpath:db/mysql").load().migrate();
            try (Connection dst = DriverManager.getConnection(targetUrl, targetUser, targetPassword)) {
                dst.setAutoCommit(false);
                try {
                    if (count(src, "event_export_state") != 1)
                        throw new IllegalStateException("Source export state missing");
                    try (var st = dst.createStatement(); var rs = st.executeQuery("SELECT last_seq FROM event_export_state WHERE id=1 FOR UPDATE")) {
                        if (!rs.next() || rs.getLong(1) != 0) throw new IllegalStateException("Target already in use");
                    }
                    for (String table : TABLES) {
                        long count = count(dst, table);
                        if (count != (table.equals("event_export_state") ? 1 : 0))
                            throw new IllegalStateException("Target not empty");
                    }
                    try (var st = dst.createStatement()) { st.executeUpdate("DELETE FROM event_export_state"); }
                    for (String table : TABLES) {
                        long copied = copy(src, dst, table);
                        if (count(dst, table) != copied) throw new IllegalStateException("Row count mismatch");
                        System.out.println(table + ": " + copied);
                    }
                    // Explicit positive IDs advance MySQL AUTO_INCREMENT automatically.
                    dst.commit();
                } catch (Exception error) {
                    dst.rollback();
                    throw error;
                }
            }
        }
    }

    private static long count(Connection db, String table) throws SQLException {
        try (var st = db.createStatement(); var rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next(); return rs.getLong(1);
        }
    }

    private static long copy(Connection src, Connection dst, String table) throws SQLException {
        try (var st = src.createStatement(); var rs = st.executeQuery("SELECT * FROM " + table)) {
            var meta = rs.getMetaData();
            int size = meta.getColumnCount();
            List<String> names = new ArrayList<>();
            for (int i = 1; i <= size; i++) names.add("`" + meta.getColumnName(i).toLowerCase(Locale.ROOT) + "`");
            String sql = "INSERT INTO " + table + " (" + String.join(",", names) + ") VALUES ("
                    + String.join(",", Collections.nCopies(size, "?")) + ")";
            try (var insert = dst.prepareStatement(sql)) {
                long count = 0;
                while (rs.next()) {
                    for (int i = 1; i <= size; i++) {
                        switch (meta.getColumnType(i)) {
                            case Types.CLOB, Types.NCLOB -> insert.setString(i, rs.getString(i));
                            case Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> insert.setBytes(i, rs.getBytes(i));
                            default -> insert.setObject(i, rs.getObject(i));
                        }
                    }
                    insert.executeUpdate();
                    count++;
                }
                return count;
            }
        }
    }
}
