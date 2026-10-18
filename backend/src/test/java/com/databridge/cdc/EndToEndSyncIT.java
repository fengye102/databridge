package com.databridge.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.databridge.copy.CopyTable;
import com.databridge.copy.FullCopyWorker;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Properties;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class EndToEndSyncIT {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4.11")
            .withDatabaseName("databridge_source")
            .withCommand("--server-id=5403", "--log-bin=mysql-bin",
                    "--binlog-format=ROW", "--binlog-row-image=FULL");
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11");

    @Test
    void copyThenReplayOverwritesOldValuesAndPreservesDeletes() throws Exception {
        var target = new PGSimpleDataSource();
        target.setURL(postgres.getJdbcUrl());
        target.setUser(postgres.getUsername());
        target.setPassword(postgres.getPassword());
        Flyway.configure().dataSource(target).load().migrate();
        executeTarget("CREATE SCHEMA business");
        executeTarget("""
                CREATE TABLE business.orders (
                    id BIGINT PRIMARY KEY, name VARCHAR(100), amount NUMERIC(18,4),
                    payload JSONB, observed_at TIMESTAMP(6), changed_at TIMESTAMP(6)
                )
                """);
        executeSource("""
                CREATE TABLE orders (
                    id BIGINT PRIMARY KEY, name VARCHAR(100), amount DECIMAL(18,4),
                    payload JSON, observed_at DATETIME(6), changed_at TIMESTAMP(6) NULL
                )
                """);
        executeSource("INSERT INTO orders(id, name) VALUES (1, 'old'), (2, 'to-delete')");
        executeSource("CREATE TABLE sync_probe (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "marker CHAR(36) NOT NULL UNIQUE)");
        try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), "root", "test");
             var statement = connection.createStatement()) {
            statement.execute("CREATE USER 'capture'@'%' IDENTIFIED BY 'capturepass'");
            statement.execute("GRANT SELECT ON databridge_source.* TO 'capture'@'%'");
            statement.execute("GRANT INSERT ON databridge_source.sync_probe TO 'capture'@'%'");
            statement.execute("""
                    GRANT RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT
                    ON *.* TO 'capture'@'%'
                    """);
        }
        var taskId = UUID.randomUUID();
        var copy = new FullCopyWorker(new DriverManagerDataSource(
                mysql.getJdbcUrl(), "root", "test"), target);
        var table = new CopyTable("databridge_source", "orders", "business", "orders");
        var applier = new EventApplier(target);
        try (var capture = new CdcCapture(taskId, "databridge_source.orders",
                settings(), new CdcEventRepository(target),
                new DriverManagerDataSource(mysql.getJdbcUrl(), "capture", "capturepass"))) {
            capture.start();
            capture.awaitReady(Duration.ofSeconds(30));

            assertEquals(1, copy.copyNextBatch(taskId, table, 1));
            assertEquals("old", targetName(1));
            executeSource("""
                    UPDATE orders SET name = 'new', amount = 1.2300, payload = '{"a":1}',
                        observed_at = '2026-10-19 12:30:01.123456',
                        changed_at = '2026-10-19 12:30:01.123456' WHERE id = 1
                    """);
            executeSource("DELETE FROM orders WHERE id = 2");
            executeSource("INSERT INTO orders(id, name) VALUES (3, 'below-watermark')");
            while (copy.copyNextBatch(taskId, table, 1) > 0) {
                // Continue from the persisted checkpoint through the fixed high watermark.
            }
            assertEquals(0, copy.copyNextBatch(taskId, table, 1));
            assertEquals("old", targetName(1));

            assertTrue(awaitCapture(capture, taskId, 3L, Duration.ofSeconds(30)));
            int applied = 0;
            int next;
            while ((next = applier.applyNextBatch(taskId, table, 2)) > 0) {
                applied += next;
            }
            assertTrue(applied >= 4);
            assertEquals("new", targetName(1));
            assertEquals(new BigDecimal("1.2300"), targetAmount(1));
            assertEquals("{\"a\": 1}", targetJson(1));
            assertEquals(LocalDateTime.of(2026, 10, 19, 12, 30, 1, 123456000), targetTime(1, false));
            assertEquals(LocalDateTime.of(2026, 10, 19, 12, 30, 1, 123456000), targetTime(1, true));
            assertNull(targetName(2));
            assertEquals("below-watermark", targetName(3));
            assertTrue(probeEventCount(taskId) > 0);
            assertNull(capture.failure());
        }
    }

    private Properties settings() {
        var properties = new Properties();
        properties.setProperty("database.hostname", mysql.getHost());
        properties.setProperty("database.port", String.valueOf(mysql.getMappedPort(3306)));
        properties.setProperty("database.user", "capture");
        properties.setProperty("database.password", "capturepass");
        properties.setProperty("database.server.id", "5403");
        properties.setProperty("topic.prefix", "databridge_end_to_end");
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

    private int probeEventCount(UUID taskId) throws Exception {
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM databridge_meta.cdc_event
                     WHERE task_id = ? AND table_name = 'databridge_source.sync_probe'
                     """)) {
            statement.setObject(1, taskId);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private boolean awaitCapture(CdcCapture capture, UUID taskId, long rowId, Duration timeout)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try (var connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 var statement = connection.prepareStatement("""
                         SELECT COUNT(*) FROM databridge_meta.cdc_event
                         WHERE task_id = ? AND row_id = ?
                         """)) {
                statement.setObject(1, taskId);
                statement.setLong(2, rowId);
                try (var result = statement.executeQuery()) {
                    result.next();
                    if (result.getInt(1) > 0) {
                        return true;
                    }
                }
            }
            if (capture.failure() != null) {
                throw new AssertionError("CDC capture failed", capture.failure());
            }
            Thread.sleep(100);
        }
        return false;
    }

    private void executeSource(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), "root", "test");
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void executeTarget(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String targetName(long id) throws Exception {
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("SELECT name FROM business.orders WHERE id = ?")) {
            statement.setLong(1, id);
            try (var result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private BigDecimal targetAmount(long id) throws Exception {
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("SELECT amount FROM business.orders WHERE id = ?")) {
            statement.setLong(1, id);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getBigDecimal(1);
            }
        }
    }

    private String targetJson(long id) throws Exception {
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("SELECT payload::text FROM business.orders WHERE id = ?")) {
            statement.setLong(1, id);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    private LocalDateTime targetTime(long id, boolean changed) throws Exception {
        String sql = changed
                ? "SELECT changed_at FROM business.orders WHERE id = ?"
                : "SELECT observed_at FROM business.orders WHERE id = ?";
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getObject(1, LocalDateTime.class);
            }
        }
    }

}
