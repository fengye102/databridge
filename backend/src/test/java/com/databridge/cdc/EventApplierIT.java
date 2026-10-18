package com.databridge.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.databridge.copy.CopyTable;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class EventApplierIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11");
    static DriverManagerDataSource target;
    static CopyTable table = new CopyTable("source", "items", "business", "items");

    @BeforeAll
    static void setup() throws Exception {
        target = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(target).load().migrate();
        execute("CREATE SCHEMA business");
        execute("""
                CREATE TABLE business.items (
                    id BIGINT PRIMARY KEY,
                    name VARCHAR(100),
                    amount NUMERIC(18,4),
                    note TEXT,
                    payload JSONB,
                    observed_at TIMESTAMP(6),
                    changed_at TIMESTAMP(6)
                )
                """);
        execute("CREATE TABLE business.unsupported (id BIGINT PRIMARY KEY, active BOOLEAN)");
    }

    @BeforeEach
    void clear() throws Exception {
        execute("TRUNCATE business.items");
        execute("DROP TRIGGER IF EXISTS reject_two ON business.items");
    }

    @Test
    void refusesReplayUntilFullCopyCompleted() throws Exception {
        var taskId = UUID.randomUUID();
        checkpoint(taskId, false);
        new CdcEventRepository(target).insertBatch(taskId, List.of(event("a", "c", 1,
                row(1, "first", null))));
        assertThrows(IllegalStateException.class,
                () -> new EventApplier(target).applyNextBatch(taskId, table, 10));
        assertEquals(0, count());
        assertEquals(0L, cursor(taskId));
    }

    @Test
    void replaysInSequenceWithIdempotentCursorAndDelete() throws Exception {
        var taskId = UUID.randomUUID();
        checkpoint(taskId, true);
        var repository = new CdcEventRepository(target);
        var first = event("b", "c", 1, row(1, "old", null));
        assertEquals(3, repository.insertBatch(taskId, List.of(first,
                event("c", "u", 1, row(1, "new", null)), event("d", "c", 2, row(2, "keep", null)))));
        assertEquals(0, repository.insertBatch(taskId, List.of(first)));
        var other = UUID.randomUUID();
        repository.insertBatch(other, List.of(event("e", "c", 999, row(999, "other", null))));
        var applier = new EventApplier(target);

        assertEquals(2, applier.applyNextBatch(taskId, table, 2));
        assertEquals("new", name(1));
        assertEquals(1, count());
        assertTrue(cursor(taskId) > 0);
        assertEquals(1, applier.applyNextBatch(taskId, table, 2));
        assertEquals("keep", name(2));
        assertEquals(0, applier.applyNextBatch(taskId, table, 2));
        assertEquals(2, count());

        repository.insertBatch(taskId, List.of(event("f", "d", 1, null)));
        assertEquals(1, applier.applyNextBatch(taskId, table, 2));
        assertNull(name(1));
        assertEquals(1, count());
    }

    @Test
    void failedSecondEventRollsBackRowsAndCursorThenRetries() throws Exception {
        var taskId = UUID.randomUUID();
        checkpoint(taskId, true);
        new CdcEventRepository(target).insertBatch(taskId, List.of(
                event("g", "c", 1, row(1, "first", null)),
                event("h", "c", 2, row(2, "second", null))));
        execute("""
                CREATE OR REPLACE FUNCTION business.reject_two_fn() RETURNS trigger AS $$
                BEGIN
                  IF NEW.id = 2 THEN RAISE EXCEPTION 'injected apply failure'; END IF;
                  RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        execute("""
                CREATE TRIGGER reject_two BEFORE INSERT OR UPDATE ON business.items
                FOR EACH ROW EXECUTE FUNCTION business.reject_two_fn()
                """);
        assertThrows(RuntimeException.class,
                () -> new EventApplier(target).applyNextBatch(taskId, table, 2));
        assertEquals(0, count());
        assertEquals(0L, cursor(taskId));

        execute("DROP TRIGGER reject_two ON business.items");
        assertEquals(2, new EventApplier(target).applyNextBatch(taskId, table, 2));
        assertEquals(2, count());
        assertEquals(0, new EventApplier(target).applyNextBatch(taskId, table, 2));
    }

    @Test
    void preservesBoundaryValuesAndRejectsMalformedRow() throws Exception {
        var taskId = UUID.randomUUID();
        checkpoint(taskId, true);
        new CdcEventRepository(target).insertBatch(taskId, List.of(event("i", "c", Long.MIN_VALUE,
                row(Long.MIN_VALUE, "汉字 😀", "1.2300"))));
        assertEquals(1, new EventApplier(target).applyNextBatch(taskId, table, 10));
        try (var connection = target.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT name, amount, note, payload::text, observed_at, changed_at
                     FROM business.items WHERE id = -9223372036854775808
                     """)) {
            assertTrue(result.next());
            assertEquals("汉字 😀", result.getString(1));
            assertEquals(new BigDecimal("1.2300"), result.getBigDecimal(2));
            assertNull(result.getString(3));
            assertEquals("{\"a\": 1}", result.getString(4));
            assertEquals(LocalDateTime.of(2026, 10, 19, 12, 30, 1, 123456000),
                    result.getObject(5, LocalDateTime.class));
            assertEquals(LocalDateTime.of(2026, 10, 19, 12, 30, 1, 123456000),
                    result.getObject(6, LocalDateTime.class));
        }
        long before = cursor(taskId);
        new CdcEventRepository(target).insertBatch(taskId, List.of(event("j", "u", Long.MIN_VALUE,
                "{\"id\":-9223372036854775808,\"name\":\"missing columns\"}")));
        assertThrows(IllegalArgumentException.class,
                () -> new EventApplier(target).applyNextBatch(taskId, table, 10));
        assertEquals(before, cursor(taskId));
        assertEquals("汉字 😀", name(Long.MIN_VALUE));
    }

    @Test
    void rejectsWrongSourceTableAndMismatchedPrimaryKeyWithoutMovingCursor() throws Exception {
        var wrongTableTask = UUID.randomUUID();
        checkpoint(wrongTableTask, true);
        new CdcEventRepository(target).insertBatch(wrongTableTask, List.of(new CdcEvent(
                "k".repeat(64), "{}", "{}", "source.other", "c", 1, row(1, "wrong", null))));
        assertThrows(IllegalArgumentException.class,
                () -> new EventApplier(target).applyNextBatch(wrongTableTask, table, 10));
        assertEquals(0L, cursor(wrongTableTask));

        var mismatchedKeyTask = UUID.randomUUID();
        checkpoint(mismatchedKeyTask, true);
        new CdcEventRepository(target).insertBatch(mismatchedKeyTask,
                List.of(event("l", "c", 2, row(1, "wrong id", null))));
        assertThrows(IllegalArgumentException.class,
                () -> new EventApplier(target).applyNextBatch(mismatchedKeyTask, table, 10));
        assertEquals(0L, cursor(mismatchedKeyTask));
        assertEquals(0, count());
    }

    @Test
    void rejectsUnsupportedTargetTypeWithoutMovingCursor() throws Exception {
        var taskId = UUID.randomUUID();
        checkpoint(taskId, true);
        new CdcEventRepository(target).insertBatch(taskId, List.of(new CdcEvent(
                "m".repeat(64), "{}", "{}", "source.items", "c", 1,
                "{\"id\":1,\"active\":true}")));
        var unsupported = new CopyTable("source", "items", "business", "unsupported");
        assertThrows(IllegalArgumentException.class,
                () -> new EventApplier(target).applyNextBatch(taskId, unsupported, 10));
        assertEquals(0L, cursor(taskId));
    }

    private static CdcEvent event(String key, String operation, long id, String after) {
        return new CdcEvent(key.repeat(64), "{}", "{}", "source.items", operation, id, after);
    }

    private static String row(long id, String name, String amount) {
        return "{\"id\":" + id + ",\"name\":\"" + name + "\",\"amount\":"
                + (amount == null ? "null" : "\"" + amount + "\"")
                + ",\"note\":null,\"payload\":\"{\\\"a\\\":1}\""
                + ",\"observed_at\":\"2026-10-19T12:30:01.123456\""
                + ",\"changed_at\":\"2026-10-19T12:30:01.123456Z\"}";
    }

    private static void checkpoint(UUID taskId, boolean completed) throws Exception {
        try (var connection = target.getConnection(); var statement = connection.prepareStatement("""
                INSERT INTO databridge_meta.full_checkpoint(task_id, high_watermark, last_id, completed)
                VALUES (?, 1, ?, ?)
                """)) {
            statement.setObject(1, taskId);
            statement.setObject(2, completed ? 1L : null);
            statement.setBoolean(3, completed);
            statement.executeUpdate();
        }
    }

    private static void execute(String sql) throws Exception {
        try (var connection = target.getConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long cursor(UUID taskId) throws Exception {
        try (var connection = target.getConnection(); var statement = connection.prepareStatement(
                "SELECT last_sequence FROM databridge_meta.event_cursor WHERE task_id = ?")) {
            statement.setObject(1, taskId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return 0;
                }
                return result.getLong(1);
            }
        }
    }

    private static int count() throws Exception {
        try (var connection = target.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM business.items")) {
            result.next();
            return result.getInt(1);
        }
    }

    private static String name(long id) throws SQLException {
        try (var connection = target.getConnection(); var statement = connection.prepareStatement(
                "SELECT name FROM business.items WHERE id = ?")) {
            statement.setLong(1, id);
            try (var result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }
}
