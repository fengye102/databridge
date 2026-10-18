package com.databridge.cdc;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
class CdcReadinessIT {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4.11")
            .withDatabaseName("databridge_source")
            .withCommand("--server-id=5404", "--log-bin=mysql-bin",
                    "--binlog-format=ROW", "--binlog-row-image=FULL");
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11");
    static PGSimpleDataSource target;

    @BeforeAll
    static void setup() throws Exception {
        target = new PGSimpleDataSource();
        target.setURL(postgres.getJdbcUrl());
        target.setUser(postgres.getUsername());
        target.setPassword(postgres.getPassword());
        Flyway.configure().dataSource(target).load().migrate();
        try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), "root", "test");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE readiness_items (id BIGINT PRIMARY KEY, name VARCHAR(100))");
            statement.execute("CREATE TABLE sync_probe (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                    + "marker CHAR(36) NOT NULL UNIQUE)");
            statement.execute("CREATE USER 'capture'@'%' IDENTIFIED BY 'capturepass'");
            statement.execute("GRANT SELECT ON databridge_source.* TO 'capture'@'%'");
            statement.execute("GRANT INSERT ON databridge_source.sync_probe TO 'capture'@'%'");
            statement.execute("""
                    GRANT RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT
                    ON *.* TO 'capture'@'%'
                    """);
        }
    }

    @Test
    void initialAndRestartedCaptureBecomeReadyBeforeCopying() throws Exception {
        var taskId = UUID.randomUUID();
        var settings = settings();
        var repository = new CdcEventRepository(target);
        var probeSource = new DriverManagerDataSource(mysql.getJdbcUrl(), "capture", "capturepass");
        try (var capture = new CdcCapture(
                taskId, "databridge_source.readiness_items", settings, repository, probeSource)) {
            capture.start();
            capture.awaitReady(Duration.ofSeconds(30));
            assertTrue(offsetRows() > 0, "ready requires a durable JDBC offset");
            awaitProbeEvent(capture, taskId);
            insert(1);
            awaitEvent(capture, taskId, 1);
        }
        try (var capture = new CdcCapture(
                taskId, "databridge_source.readiness_items", settings, repository, probeSource)) {
            capture.start();
            capture.awaitReady(Duration.ofSeconds(30));
            insert(2);
            awaitEvent(capture, taskId, 2);
        }
    }

    private static void awaitProbeEvent(CdcCapture capture, UUID taskId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            try (var connection = target.getConnection(); var statement = connection.prepareStatement("""
                    SELECT COUNT(*) FROM databridge_meta.cdc_event
                    WHERE task_id = ? AND table_name = 'databridge_source.sync_probe'
                    """)) {
                statement.setObject(1, taskId);
                try (var result = statement.executeQuery()) {
                    result.next();
                    if (result.getInt(1) > 0) {
                        return;
                    }
                }
            }
            if (capture.failure() != null) {
                throw new AssertionError("CDC Engine failed", capture.failure());
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Readiness probe event missing");
    }

    private static void insert(long id) throws Exception {
        try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), "root", "test");
             var statement = connection.prepareStatement(
                     "INSERT INTO readiness_items(id, name) VALUES (?, 'ready')")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        }
    }

    private static void awaitEvent(CdcCapture capture, UUID taskId, long id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            try (var connection = target.getConnection(); var statement = connection.prepareStatement("""
                    SELECT COUNT(*) FROM databridge_meta.cdc_event
                    WHERE task_id = ? AND row_id = ?
                    """)) {
                statement.setObject(1, taskId);
                statement.setLong(2, id);
                try (var result = statement.executeQuery()) {
                    result.next();
                    if (result.getInt(1) > 0) {
                        return;
                    }
                }
            }
            if (capture.failure() != null) {
                throw new AssertionError("CDC Engine failed", capture.failure());
            }
            Thread.sleep(100);
        }
        throw new AssertionError("CDC event missing after readiness: " + id);
    }

    private static int offsetRows() throws Exception {
        try (var connection = target.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery(
                     "SELECT COUNT(*) FROM databridge_meta.debezium_offset_storage")) {
            result.next();
            return result.getInt(1);
        }
    }

    private static Properties settings() {
        var properties = new Properties();
        properties.setProperty("database.hostname", mysql.getHost());
        properties.setProperty("database.port", String.valueOf(mysql.getMappedPort(3306)));
        properties.setProperty("database.user", "capture");
        properties.setProperty("database.password", "capturepass");
        properties.setProperty("database.server.id", "5404");
        properties.setProperty("topic.prefix", "databridge_readiness");
        properties.setProperty("database.include.list", "databridge_source");
        properties.setProperty("table.include.list", "databridge_source.readiness_items");
        properties.setProperty("offset.storage.jdbc.connection.url", postgres.getJdbcUrl());
        properties.setProperty("offset.storage.jdbc.connection.user", postgres.getUsername());
        properties.setProperty("offset.storage.jdbc.connection.password", postgres.getPassword());
        properties.setProperty("schema.history.internal.jdbc.connection.url", postgres.getJdbcUrl());
        properties.setProperty("schema.history.internal.jdbc.connection.user", postgres.getUsername());
        properties.setProperty("schema.history.internal.jdbc.connection.password", postgres.getPassword());
        return properties;
    }
}
