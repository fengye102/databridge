package com.databridge.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class CdcCaptureIT {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4.11")
            .withDatabaseName("databridge_source")
            .withCommand("--server-id=5402", "--log-bin=mysql-bin",
                    "--binlog-format=ROW", "--binlog-row-image=FULL");
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11");

    static PGSimpleDataSource target;

    @BeforeAll
    static void prepare() throws Exception {
        target = new PGSimpleDataSource();
        target.setURL(postgres.getJdbcUrl());
        target.setUser(postgres.getUsername());
        target.setPassword(postgres.getPassword());
        Flyway.configure().dataSource(target).load().migrate();
        try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), "root", "test");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, name VARCHAR(100))");
            statement.execute("CREATE USER 'capture'@'%' IDENTIFIED BY 'capturepass'");
            statement.execute("GRANT SELECT ON databridge_source.* TO 'capture'@'%'");
            statement.execute("""
                    GRANT RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT
                    ON *.* TO 'capture'@'%'
                    """);
        }
    }

    @Test
    void capturesOrderedDmlAndResumesAfterRestart() throws Exception {
        var taskId = UUID.randomUUID();
        var repository = new CdcEventRepository(target);
        var settings = settings();

        try (var capture = new CdcCapture(taskId, "databridge_source.orders", settings, repository)) {
            capture.start();
            waitForFirstCapturedCanary(capture, taskId);
            execute("INSERT INTO orders (id, name) VALUES (1, 'old')");
            execute("UPDATE orders SET name = 'new' WHERE id = 1");
            execute("DELETE FROM orders WHERE id = 1");
            awaitRows(capture, taskId, 1L, 3, Duration.ofSeconds(30));
            assertEquals(List.of("c", "u", "d"), operations(taskId, 1L));
            assertEquals(0, repository.insertBatch(taskId, List.of(firstEvent(taskId, 1L))));
            assertEquals(List.of("c", "u", "d"), operations(taskId, 1L));

            try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), "root", "test");
                 var statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                statement.execute("INSERT INTO orders (id, name) VALUES (3, 'same transaction')");
                statement.execute("INSERT INTO orders (id, name) VALUES (4, 'same transaction')");
                connection.commit();
            }
            awaitRows(capture, taskId, 3L, 1, Duration.ofSeconds(30));
            awaitRows(capture, taskId, 4L, 1, Duration.ofSeconds(30));
            assertNotEquals(eventKey(taskId, 3L), eventKey(taskId, 4L));
            assertTrue(sequence(taskId, 3L) < sequence(taskId, 4L));

            execute("INSERT INTO orders (id, name) VALUES (5, 'original')");
            awaitRows(capture, taskId, 5L, 1, Duration.ofSeconds(30));
            try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), "root", "test");
                 var statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                statement.execute("UPDATE orders SET name = 'first update' WHERE id = 5");
                statement.execute("UPDATE orders SET name = 'second update' WHERE id = 5");
                connection.commit();
            }
            awaitRows(capture, taskId, 5L, 3, Duration.ofSeconds(30));
            assertEquals(List.of("c", "u", "u"), operations(taskId, 5L));
            assertEquals(3, uniqueEventKeys(taskId, 5L));
        }

        try (var capture = new CdcCapture(taskId, "databridge_source.orders", settings, repository)) {
            capture.start();
            execute("INSERT INTO orders (id, name) VALUES (2, 'after restart')");
            awaitRows(capture, taskId, 2L, 1, Duration.ofSeconds(30));
            assertEquals(List.of("c", "u", "d"), operations(taskId, 1L));
            assertEquals(List.of("c"), operations(taskId, 2L));
            assertNull(capture.failure());
        }

        executePostgres("""
                CREATE FUNCTION databridge_meta.reject_row_99() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.row_id = 99 THEN
                        RAISE EXCEPTION 'injected event insert failure';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        executePostgres("""
                CREATE TRIGGER reject_row_99 BEFORE INSERT ON databridge_meta.cdc_event
                FOR EACH ROW EXECUTE FUNCTION databridge_meta.reject_row_99()
                """);
        try (var capture = new CdcCapture(taskId, "databridge_source.orders", settings, repository)) {
            capture.start();
            execute("INSERT INTO orders (id, name) VALUES (99, 'retry after failure')");
            awaitFailure(capture, Duration.ofSeconds(20));
            assertEquals(List.of(), operations(taskId, 99L));
        }
        executePostgres("DROP TRIGGER reject_row_99 ON databridge_meta.cdc_event");
        executePostgres("DROP FUNCTION databridge_meta.reject_row_99()");
        try (var capture = new CdcCapture(taskId, "databridge_source.orders", settings, repository)) {
            capture.start();
            awaitRows(capture, taskId, 99L, 1, Duration.ofSeconds(30));
            assertEquals(List.of("c"), operations(taskId, 99L));
        }
    }

    private void waitForFirstCapturedCanary(CdcCapture capture, UUID taskId) throws Exception {
        for (int attempt = 0; attempt < 15; attempt++) {
            long id = 1000L + attempt;
            execute("INSERT INTO orders (id, name) VALUES (" + id + ", 'canary')");
            if (awaitRowsIfAvailable(capture, taskId, id, 1, Duration.ofSeconds(2))) {
                return;
            }
        }
        throw new AssertionError("Debezium did not capture a readiness canary", capture.failure());
    }

    private void execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), "root", "test");
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void executePostgres(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String eventKey(UUID taskId, long rowId) throws Exception {
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("""
                     SELECT event_key FROM databridge_meta.cdc_event
                     WHERE task_id = ? AND row_id = ?
                     """)) {
            statement.setObject(1, taskId);
            statement.setLong(2, rowId);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    private long sequence(UUID taskId, long rowId) throws Exception {
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("""
                     SELECT sequence FROM databridge_meta.cdc_event
                     WHERE task_id = ? AND row_id = ?
                     """)) {
            statement.setObject(1, taskId);
            statement.setLong(2, rowId);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private int uniqueEventKeys(UUID taskId, long rowId) throws Exception {
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("""
                     SELECT count(DISTINCT event_key) FROM databridge_meta.cdc_event
                     WHERE task_id = ? AND row_id = ?
                     """)) {
            statement.setObject(1, taskId);
            statement.setLong(2, rowId);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private CdcEvent firstEvent(UUID taskId, long rowId) throws Exception {
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("""
                     SELECT event_key, source_partition::text, source_offset::text,
                            table_name, operation, row_id, after_row::text
                     FROM databridge_meta.cdc_event
                     WHERE task_id = ? AND row_id = ?
                     ORDER BY sequence LIMIT 1
                     """)) {
            statement.setObject(1, taskId);
            statement.setLong(2, rowId);
            try (var result = statement.executeQuery()) {
                result.next();
                return new CdcEvent(
                        result.getString(1), result.getString(2), result.getString(3),
                        result.getString(4), result.getString(5), result.getLong(6),
                        result.getString(7));
            }
        }
    }

    private void awaitFailure(CdcCapture capture, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline && capture.failure() == null) {
            Thread.sleep(100);
        }
        assertTrue(capture.failure() != null, "Expected injected event insert failure");
    }

    private void awaitRows(CdcCapture capture, UUID taskId, long rowId, int expected, Duration timeout)
            throws Exception {
        assertTrue(awaitRowsIfAvailable(capture, taskId, rowId, expected, timeout),
                "Timed out waiting for CDC row " + rowId + "; engine failure=" + capture.failure());
    }

    private boolean awaitRowsIfAvailable(
            CdcCapture capture, UUID taskId, long rowId, int expected, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (operations(taskId, rowId).size() >= expected) {
                return true;
            }
            if (capture.failure() != null) {
                throw new AssertionError("Debezium Engine failed", capture.failure());
            }
            Thread.sleep(100);
        }
        return false;
    }

    private List<String> operations(UUID taskId, long rowId) throws Exception {
        var values = new ArrayList<String>();
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("""
                     SELECT operation FROM databridge_meta.cdc_event
                     WHERE task_id = ? AND row_id = ? ORDER BY sequence
                     """)) {
            statement.setObject(1, taskId);
            statement.setLong(2, rowId);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    values.add(result.getString(1));
                }
            }
        }
        return values;
    }

    private Properties settings() {
        var properties = new Properties();
        properties.setProperty("database.hostname", mysql.getHost());
        properties.setProperty("database.port", String.valueOf(mysql.getMappedPort(3306)));
        properties.setProperty("database.user", "capture");
        properties.setProperty("database.password", "capturepass");
        properties.setProperty("database.server.id", "5402");
        properties.setProperty("topic.prefix", "databridge_test");
        properties.setProperty("database.include.list", "databridge_source");
        properties.setProperty("table.include.list", "databridge_source.orders");
        properties.setProperty("offset.storage.jdbc.connection.url", postgres.getJdbcUrl());
        properties.setProperty("offset.storage.jdbc.connection.user", postgres.getUsername());
        properties.setProperty("offset.storage.jdbc.connection.password", postgres.getPassword());
        properties.setProperty("schema.history.internal.jdbc.connection.url", postgres.getJdbcUrl());
        properties.setProperty("schema.history.internal.jdbc.connection.user", postgres.getUsername());
        properties.setProperty("schema.history.internal.jdbc.connection.password", postgres.getPassword());
        return properties;
    }
}
