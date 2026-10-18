package com.databridge.copy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class FullCopyWorkerIT {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4.11");
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11");

    static DriverManagerDataSource source;
    static DriverManagerDataSource target;
    static CopyTable table;

    @BeforeAll
    static void setup() throws Exception {
        source = new DriverManagerDataSource(mysql.getJdbcUrl()
                + "?sessionVariables=time_zone='%2B08:00'", mysql.getUsername(), mysql.getPassword());
        target = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(target).load().migrate();
        table = new CopyTable(mysql.getDatabaseName(), "copy_items", "business", "copy_items");
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE copy_items (
                        id BIGINT PRIMARY KEY,
                        name VARCHAR(100),
                        amount DECIMAL(18,4),
                        note TEXT,
                        payload JSON,
                        observed_at DATETIME(6),
                        changed_at TIMESTAMP(6) NULL
                    )
                    """);
            statement.execute("CREATE TABLE copy_wrong_key (id INTEGER PRIMARY KEY, name VARCHAR(100))");
            statement.execute("CREATE TABLE copy_unsupported (id BIGINT PRIMARY KEY, active BOOLEAN)");
        }
        try (var connection = target.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA business");
            statement.execute("""
                    CREATE TABLE business.copy_items (
                        id BIGINT PRIMARY KEY,
                        name VARCHAR(100),
                        amount DECIMAL(18,4),
                        note TEXT,
                        payload JSONB,
                        observed_at TIMESTAMP(6),
                        changed_at TIMESTAMP(6)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE business.copy_items_narrow (
                        id BIGINT PRIMARY KEY,
                        name VARCHAR(100),
                        amount DECIMAL(5,2),
                        note TEXT,
                        payload JSONB,
                        observed_at TIMESTAMP(6),
                        changed_at TIMESTAMP(6)
                    )
                    """);
            statement.execute("CREATE TABLE business.copy_wrong_key (id INTEGER PRIMARY KEY, name VARCHAR(100))");
            statement.execute("CREATE TABLE business.copy_unsupported (id BIGINT PRIMARY KEY, active BOOLEAN)");
        }
    }

    @BeforeEach
    void clearBusinessRows() throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DELETE FROM copy_items");
        }
        try (var connection = target.getConnection(); var statement = connection.createStatement()) {
            statement.execute("TRUNCATE business.copy_items");
            statement.execute("TRUNCATE business.copy_items_narrow");
            statement.execute("DROP TRIGGER IF EXISTS reject_two ON business.copy_items");
        }
    }

    @Test
    void flywayCreatesNullableCheckpointAndUniqueTaskId() throws Exception {
        var taskId = UUID.randomUUID();
        try (var connection = target.getConnection(); var statement = connection.prepareStatement("""
                INSERT INTO databridge_meta.full_checkpoint(task_id, high_watermark, last_id, completed)
                VALUES (?, 10, NULL, FALSE)
                """)) {
            statement.setObject(1, taskId);
            assertEquals(1, statement.executeUpdate());
            assertThrows(SQLException.class, statement::executeUpdate);
        }
    }

    @Test
    void copiesSignedSparseKeysWithoutExtendingHighWatermark() throws Exception {
        insert(Long.MIN_VALUE, "最小值", new BigDecimal("1.2300"), null, "{\"a\":1}",
                LocalDateTime.of(2026, 10, 19, 12, 30, 1, 123456000));
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("SET time_zone = '+08:00'");
            statement.execute("""
                    UPDATE copy_items SET changed_at = '2026-10-19 20:30:01.123456'
                    WHERE id = -9223372036854775808
                    """);
        }
        insert(0, "zero", null, "", null, null);
        insert(99, "last", new BigDecimal("9.9900"), "note", "[1,2]", null);
        var taskId = UUID.randomUUID();
        var worker = new FullCopyWorker(source, target);

        assertEquals(2, worker.copyNextBatch(taskId, table, 2));
        assertEquals(99L, highWatermark(taskId));
        assertEquals(0L, lastId(taskId));
        assertFalse(completed(taskId));

        insert(100, "late", null, null, null, null);
        assertEquals(1, new FullCopyWorker(source, target).copyNextBatch(taskId, table, 2));
        assertEquals(99L, lastId(taskId));
        assertEquals(0, worker.copyNextBatch(taskId, table, 2));
        assertTrue(completed(taskId));
        assertEquals(0, worker.copyNextBatch(taskId, table, 2));

        try (var connection = target.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("""
                     SELECT id, name, amount, note, payload::text, observed_at, changed_at
                     FROM business.copy_items ORDER BY id
                     """)) {
            assertTrue(rows.next());
            assertEquals(Long.MIN_VALUE, rows.getLong(1));
            assertEquals("最小值", rows.getString(2));
            assertEquals(new BigDecimal("1.2300"), rows.getBigDecimal(3));
            assertNull(rows.getString(4));
            assertEquals("{\"a\": 1}", rows.getString(5));
            assertEquals(LocalDateTime.of(2026, 10, 19, 12, 30, 1, 123456000),
                    rows.getObject(6, LocalDateTime.class));
            assertEquals(LocalDateTime.of(2026, 10, 19, 12, 30, 1, 123456000),
                    rows.getObject(7, LocalDateTime.class));
            assertTrue(rows.next());
            assertEquals(0, rows.getLong(1));
            assertTrue(rows.next());
            assertEquals(99, rows.getLong(1));
            assertFalse(rows.next());
        }
    }

    @Test
    void rollsBackEntireBatchAndRetriesFromUnchangedCheckpoint() throws Exception {
        insert(1, "first", null, null, null, null);
        insert(2, "second", null, null, null, null);
        var taskId = UUID.randomUUID();
        try (var connection = target.getConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE OR REPLACE FUNCTION business.reject_two_fn() RETURNS trigger AS $$
                    BEGIN
                      IF NEW.id = 2 THEN RAISE EXCEPTION 'injected copy failure'; END IF;
                      RETURN NEW;
                    END;
                    $$ LANGUAGE plpgsql
                    """);
            statement.execute("""
                    CREATE TRIGGER reject_two BEFORE INSERT OR UPDATE ON business.copy_items
                    FOR EACH ROW EXECUTE FUNCTION business.reject_two_fn()
                    """);
        }
        assertThrows(RuntimeException.class, () -> new FullCopyWorker(source, target)
                .copyNextBatch(taskId, table, 2));
        assertNull(lastId(taskId));
        assertEquals(0, targetCount());

        insert(3, "after-watermark", null, null, null, null);
        try (var connection = target.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER reject_two ON business.copy_items");
        }
        assertEquals(2, new FullCopyWorker(source, target).copyNextBatch(taskId, table, 2));
        assertEquals(2L, lastId(taskId));
        assertEquals(0, new FullCopyWorker(source, target).copyNextBatch(taskId, table, 2));
        assertTrue(completed(taskId));
        assertEquals(2, targetCount());
    }

    @Test
    void failedLaterBatchKeepsEarlierCommittedCheckpoint() throws Exception {
        insert(1, "one", null, null, null, null);
        insert(2, "two", null, null, null, null);
        insert(3, "three", null, null, null, null);
        var taskId = UUID.randomUUID();
        assertEquals(1, new FullCopyWorker(source, target).copyNextBatch(taskId, table, 1));
        assertEquals(1L, lastId(taskId));

        try (var connection = target.getConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE OR REPLACE FUNCTION business.reject_two_fn() RETURNS trigger AS $$
                    BEGIN
                      IF NEW.id = 2 THEN RAISE EXCEPTION 'injected copy failure'; END IF;
                      RETURN NEW;
                    END;
                    $$ LANGUAGE plpgsql
                    """);
            statement.execute("""
                    CREATE TRIGGER reject_two BEFORE INSERT OR UPDATE ON business.copy_items
                    FOR EACH ROW EXECUTE FUNCTION business.reject_two_fn()
                    """);
        }
        assertThrows(RuntimeException.class, () -> new FullCopyWorker(source, target)
                .copyNextBatch(taskId, table, 2));
        assertEquals(1L, lastId(taskId));
        assertEquals(1, targetCount());

        try (var connection = target.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER reject_two ON business.copy_items");
        }
        assertEquals(2, new FullCopyWorker(source, target).copyNextBatch(taskId, table, 2));
        assertEquals(3L, lastId(taskId));
        assertEquals(3, targetCount());
    }

    @Test
    void emptySourceCompletesWithNullHighWatermark() throws Exception {
        var taskId = UUID.randomUUID();
        assertEquals(0, new FullCopyWorker(source, target).copyNextBatch(taskId, table, 2));
        assertNull(highWatermark(taskId));
        assertNull(lastId(taskId));
        assertTrue(completed(taskId));
    }

    @Test
    void copiesTenThousandRowsInBoundedBatches() throws Exception {
        try (var connection = source.getConnection(); var statement = connection.prepareStatement(
                "INSERT INTO copy_items(id, name) VALUES (?, ?)")) {
            for (int id = 1; id <= 10_000; id++) {
                statement.setLong(1, id);
                statement.setString(2, "row-" + id);
                statement.addBatch();
            }
            statement.executeBatch();
        }
        var taskId = UUID.randomUUID();
        var worker = new FullCopyWorker(source, target);
        int copied = 0;
        int batches = 0;
        int next;
        while ((next = worker.copyNextBatch(taskId, table, 1_000)) > 0) {
            copied += next;
            batches++;
        }
        assertEquals(10_000, copied);
        assertEquals(10, batches);
        assertEquals(10_000, targetCount());
        assertEquals(10_000L, lastId(taskId));
        assertTrue(completed(taskId));
    }

    @Test
    void rejectsNarrowTargetDecimalBeforeWriting() throws Exception {
        insert(7, "decimal", new BigDecimal("1.2345"), null, null, null);
        var taskId = UUID.randomUUID();
        var narrow = new CopyTable(mysql.getDatabaseName(), "copy_items", "business", "copy_items_narrow");
        assertThrows(IllegalArgumentException.class,
                () -> new FullCopyWorker(source, target).copyNextBatch(taskId, narrow, 10));
        assertNull(lastId(taskId));
        try (var connection = target.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM business.copy_items_narrow")) {
            result.next();
            assertEquals(0, result.getInt(1));
        }
    }

    @Test
    void rejectsNonBigintKeyAndUnsupportedType() throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO copy_wrong_key VALUES (1, 'wrong')");
            statement.execute("INSERT INTO copy_unsupported VALUES (1, TRUE)");
        }
        var wrongKey = new CopyTable(mysql.getDatabaseName(), "copy_wrong_key", "business", "copy_wrong_key");
        var unsupported = new CopyTable(mysql.getDatabaseName(), "copy_unsupported", "business", "copy_unsupported");
        assertThrows(IllegalArgumentException.class,
                () -> new FullCopyWorker(source, target).copyNextBatch(UUID.randomUUID(), wrongKey, 10));
        assertThrows(IllegalArgumentException.class,
                () -> new FullCopyWorker(source, target).copyNextBatch(UUID.randomUUID(), unsupported, 10));
    }

    private void insert(long id, String name, BigDecimal amount, String note, String payload,
                        LocalDateTime observedAt) throws Exception {
        try (var connection = source.getConnection(); var statement = connection.prepareStatement("""
                INSERT INTO copy_items(id, name, amount, note, payload, observed_at) VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setLong(1, id);
            statement.setString(2, name);
            statement.setBigDecimal(3, amount);
            statement.setString(4, note);
            statement.setString(5, payload);
            statement.setObject(6, observedAt);
            statement.executeUpdate();
        }
    }

    private Long highWatermark(UUID taskId) throws Exception {
        return checkpointLong(taskId, "high_watermark");
    }

    private Long lastId(UUID taskId) throws Exception {
        return checkpointLong(taskId, "last_id");
    }

    private Long checkpointLong(UUID taskId, String column) throws Exception {
        try (var connection = target.getConnection(); var statement = connection.prepareStatement(
                "SELECT " + column + " FROM databridge_meta.full_checkpoint WHERE task_id = ?")) {
            statement.setObject(1, taskId);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getObject(1, Long.class);
            }
        }
    }

    private boolean completed(UUID taskId) throws Exception {
        try (var connection = target.getConnection(); var statement = connection.prepareStatement(
                "SELECT completed FROM databridge_meta.full_checkpoint WHERE task_id = ?")) {
            statement.setObject(1, taskId);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getBoolean(1);
            }
        }
    }

    private int targetCount() throws Exception {
        try (var connection = target.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM business.copy_items")) {
            result.next();
            return result.getInt(1);
        }
    }
}
