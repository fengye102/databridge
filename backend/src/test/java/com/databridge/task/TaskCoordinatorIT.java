package com.databridge.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.databridge.copy.CopyTable;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class TaskCoordinatorIT {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4.11")
            .withDatabaseName("databridge_source")
            .withCommand("--server-id=5410", "--log-bin=mysql-bin",
                    "--binlog-format=ROW", "--binlog-row-image=FULL");
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11");
    static PGSimpleDataSource target;
    static DriverManagerDataSource source;

    @BeforeAll
    static void setup() throws Exception {
        target = new PGSimpleDataSource();
        target.setURL(postgres.getJdbcUrl());
        target.setUser(postgres.getUsername());
        target.setPassword(postgres.getPassword());
        Flyway.configure().dataSource(target).load().migrate();
        executeTarget("CREATE SCHEMA business");
        executeTarget("CREATE TABLE business.items (id BIGINT PRIMARY KEY, name VARCHAR(100))");
        executeTarget("CREATE TABLE business.recover_items (id BIGINT PRIMARY KEY, name VARCHAR(100))");
        executeTarget("CREATE TABLE business.write_fail_items (id BIGINT PRIMARY KEY, name VARCHAR(100))");
        executeSource("CREATE TABLE items (id BIGINT PRIMARY KEY, name VARCHAR(100))");
        executeSource("CREATE TABLE recover_items (id BIGINT PRIMARY KEY, name VARCHAR(100))");
        executeSource("CREATE TABLE write_fail_items (id BIGINT PRIMARY KEY, name VARCHAR(100))");
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
        source = new DriverManagerDataSource(mysql.getJdbcUrl(), "capture", "capturepass");
    }

    @Test
    void pausesCaptureContinuesAndRestartResumesFromDurableState() throws Exception {
        for (long id = 1; id <= 150; id++) {
            executeSource("INSERT INTO items(id, name) VALUES (" + id + ", 'old')");
        }
        var table = new CopyTable("databridge_source", "items", "business", "items");
        UUID taskId;
        try (var coordinator = new TaskCoordinator(source, target, settings("items"), 1)) {
            taskId = coordinator.create(table);
            assertEquals("CREATED", status(taskId));
            coordinator.start(taskId);
            awaitStatus(taskId, "COPYING", Duration.ofSeconds(35));
            coordinator.pause(taskId);
            awaitStatus(taskId, "PAUSED", Duration.ofSeconds(5));
            executeSource("UPDATE items SET name = 'changed' WHERE id = 1");
            executeSource("DELETE FROM items WHERE id = 2");
            awaitEvent(taskId, 1, Duration.ofSeconds(20));
            assertNotEquals("changed", targetName("items", 1));
            coordinator.resume(taskId);
            awaitStatus(taskId, "STREAMING", Duration.ofSeconds(60));
            awaitTargetName("items", 1, "changed", Duration.ofSeconds(20));
            try (var duplicate = new TaskCoordinator(source, target, settings("items"), 5)) {
                assertThrows(IllegalStateException.class, () -> duplicate.start(taskId));
            }
            assertEquals(null, targetName("items", 2));
            assertEquals("old", targetName("items", 150));
            coordinator.stop(taskId);
            awaitStatus(taskId, "STOPPED", Duration.ofSeconds(10));
        }
        // A previous process may disappear while its last durable phase is still active.
        setStatus(taskId, "COPYING");
        executeSource("UPDATE items SET name = 'after-stop' WHERE id = 3");
        try (var restarted = new TaskCoordinator(source, target, settings("items"), 5)) {
            restarted.start(taskId);
            awaitStatus(taskId, "STREAMING", Duration.ofSeconds(45));
            awaitTargetName("items", 3, "after-stop", Duration.ofSeconds(20));
            assertTrue(checkpointCompleted(taskId));
        }
        awaitStatus(taskId, "STOPPED", Duration.ofSeconds(5));
        setPaused(taskId);
        try (var pausedRestart = new TaskCoordinator(source, target, settings("items"), 5)) {
            pausedRestart.start(taskId);
            awaitStatus(taskId, "PAUSED", Duration.ofSeconds(45));
            executeSource("UPDATE items SET name = 'after-pause-restart' WHERE id = 4");
            awaitEvent(taskId, 4, Duration.ofSeconds(20));
            assertEquals("old", targetName("items", 4));
            pausedRestart.resume(taskId);
            awaitStatus(taskId, "STREAMING", Duration.ofSeconds(30));
            awaitTargetName("items", 4, "after-pause-restart", Duration.ofSeconds(20));
        }
    }

    @Test
    void captureFailurePersistsReasonAndCanRestart() throws Exception {
        executeSource("INSERT INTO recover_items(id, name) VALUES (1, 'recover')");
        var table = new CopyTable("databridge_source", "recover_items", "business", "recover_items");
        UUID taskId;
        var bad = settings("recover_items");
        bad.setProperty("database.password", "wrong-password");
        try (var coordinator = new TaskCoordinator(source, target, bad, 10)) {
            taskId = coordinator.create(table);
            coordinator.start(taskId);
            awaitStatus(taskId, "FAILED", Duration.ofSeconds(45));
            assertTrue(errorMessage(taskId) != null && !errorMessage(taskId).isBlank());
        }
        try (var restarted = new TaskCoordinator(source, target, settings("recover_items"), 10)) {
            restarted.start(taskId);
            awaitStatus(taskId, "STREAMING", Duration.ofSeconds(45));
            awaitTargetName("recover_items", 1, "recover", Duration.ofSeconds(10));
        }
    }

    @Test
    void targetWriteFailureKeepsCheckpointAndMarksTaskFailed() throws Exception {
        executeSource("INSERT INTO write_fail_items(id, name) VALUES (1, 'before-failure')");
        var table = new CopyTable("databridge_source", "write_fail_items",
                "business", "write_fail_items");
        try (var coordinator = new TaskCoordinator(source, target, settings("write_fail_items"), 10)) {
            UUID taskId = coordinator.create(table);
            executeTarget("DROP TABLE business.write_fail_items");
            coordinator.start(taskId);
            awaitStatus(taskId, "FAILED", Duration.ofSeconds(45));
            assertTrue(errorMessage(taskId) != null && !errorMessage(taskId).isBlank());
            assertEquals("COPYING", taskString(taskId, "resume_status"));
        }
    }

    private static Properties settings(String table) {
        var settings = new Properties();
        settings.setProperty("database.hostname", mysql.getHost());
        settings.setProperty("database.port", String.valueOf(mysql.getMappedPort(3306)));
        settings.setProperty("database.user", "capture");
        settings.setProperty("database.password", "capturepass");
        settings.setProperty("database.server.id", table.equals("items") ? "5411" : "5412");
        settings.setProperty("topic.prefix", "databridge_" + table);
        settings.setProperty("database.include.list", "databridge_source");
        settings.setProperty("offset.storage.jdbc.connection.url", postgres.getJdbcUrl());
        settings.setProperty("offset.storage.jdbc.connection.user", postgres.getUsername());
        settings.setProperty("offset.storage.jdbc.connection.password", postgres.getPassword());
        settings.setProperty("schema.history.internal.jdbc.connection.url", postgres.getJdbcUrl());
        settings.setProperty("schema.history.internal.jdbc.connection.user", postgres.getUsername());
        settings.setProperty("schema.history.internal.jdbc.connection.password", postgres.getPassword());
        return settings;
    }

    private static void awaitStatus(UUID taskId, String expected, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String actual = status(taskId);
            if (expected.equals(actual)) {
                return;
            }
            if ("FAILED".equals(actual) && !"FAILED".equals(expected)) {
                throw new AssertionError("Task failed: " + errorMessage(taskId));
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Expected " + expected + ", got " + status(taskId));
    }

    private static void awaitTargetName(String table, long id, String expected, Duration timeout)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (expected.equals(targetName(table, id))) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Target row " + id + " did not become " + expected);
    }

    private static void awaitEvent(UUID taskId, long id, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try (var connection = target.getConnection(); var query = connection.prepareStatement("""
                    SELECT 1 FROM databridge_meta.cdc_event
                    WHERE task_id = ? AND row_id = ? AND table_name = 'databridge_source.items'
                    LIMIT 1
                    """)) {
                query.setObject(1, taskId);
                query.setLong(2, id);
                try (var result = query.executeQuery()) {
                    if (result.next()) {
                        return;
                    }
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("CDC event missing for " + id);
    }

    private static String status(UUID taskId) throws Exception {
        return taskString(taskId, "status");
    }

    private static String errorMessage(UUID taskId) throws Exception {
        return taskString(taskId, "error_message");
    }

    private static String taskString(UUID taskId, String column) throws Exception {
        try (var connection = target.getConnection(); var query = connection.prepareStatement(
                "SELECT " + column + " FROM databridge_meta.sync_task WHERE task_id = ?")) {
            query.setObject(1, taskId);
            try (var result = query.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    private static boolean checkpointCompleted(UUID taskId) throws Exception {
        try (var connection = target.getConnection(); var query = connection.prepareStatement(
                "SELECT completed FROM databridge_meta.full_checkpoint WHERE task_id = ?")) {
            query.setObject(1, taskId);
            try (var result = query.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private static void setStatus(UUID taskId, String status) throws Exception {
        try (var connection = target.getConnection(); var update = connection.prepareStatement(
                "UPDATE databridge_meta.sync_task SET status = ? WHERE task_id = ?")) {
            update.setString(1, status);
            update.setObject(2, taskId);
            update.executeUpdate();
        }
    }

    private static void setPaused(UUID taskId) throws Exception {
        try (var connection = target.getConnection(); var update = connection.prepareStatement("""
                UPDATE databridge_meta.sync_task
                SET status = 'PAUSED', resume_status = 'STREAMING' WHERE task_id = ?
                """)) {
            update.setObject(1, taskId);
            update.executeUpdate();
        }
    }

    private static String targetName(String table, long id) throws Exception {
        try (var connection = target.getConnection(); var query = connection.prepareStatement(
                "SELECT name FROM business.\"" + table + "\" WHERE id = ?")) {
            query.setLong(1, id);
            try (var result = query.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private static void executeSource(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), "root", "test");
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void executeTarget(String sql) throws Exception {
        try (var connection = target.getConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
