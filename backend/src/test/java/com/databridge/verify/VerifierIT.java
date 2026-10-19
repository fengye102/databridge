package com.databridge.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.databridge.copy.CopyTable;
import com.databridge.task.TaskCoordinator;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
class VerifierIT {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4.11")
            .withDatabaseName("databridge_source")
            .withCommand("--server-id=5430", "--log-bin=mysql-bin",
                    "--binlog-format=ROW", "--binlog-row-image=FULL");
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11");
    static DriverManagerDataSource source;
    static PGSimpleDataSource target;

    @BeforeAll
    static void setup() throws Exception {
        target = new PGSimpleDataSource();
        target.setURL(postgres.getJdbcUrl());
        target.setUser(postgres.getUsername());
        target.setPassword(postgres.getPassword());
        Flyway.configure().dataSource(target).load().migrate();
        try (var pg = target.getConnection(); var statement = pg.createStatement()) {
            statement.execute("CREATE SCHEMA business");
            statement.execute("""
                    CREATE TABLE business.verify_items (
                        id BIGINT PRIMARY KEY, name VARCHAR(100), amount NUMERIC(10,2),
                        payload JSONB, happened_at TIMESTAMP(3))
                    """);
        }
        try (var db = DriverManager.getConnection(mysql.getJdbcUrl(), "root", "test");
             var statement = db.createStatement()) {
            statement.execute("""
                    CREATE TABLE verify_items (
                        id BIGINT PRIMARY KEY, name VARCHAR(100), amount DECIMAL(10,2),
                        payload JSON, happened_at DATETIME(3))
                    """);
            statement.execute("CREATE TABLE sync_probe (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                    + "marker CHAR(36) NOT NULL UNIQUE)");
            statement.execute("CREATE USER 'capture'@'%' IDENTIFIED BY 'capturepass'");
            statement.execute("GRANT SELECT ON databridge_source.* TO 'capture'@'%'");
            statement.execute("GRANT INSERT ON databridge_source.sync_probe TO 'capture'@'%'");
            statement.execute("GRANT RELOAD, SHOW DATABASES, REPLICATION SLAVE, "
                    + "REPLICATION CLIENT ON *.* TO 'capture'@'%'");
            statement.execute("""
                    INSERT INTO verify_items VALUES
                    (1, 'é', 1.20, '{"b":2,"a":1.0}', '2026-10-19 12:00:00.123'),
                    (2, 'second', 2.00, '{"x":true}', '2026-10-19 12:00:01.000'),
                    (3, 'third', NULL, NULL, NULL)
                    """);
        }
        source = new DriverManagerDataSource(mysql.getJdbcUrl(), "capture", "capturepass");
    }

    @Test
    void reportsQuietConsistencyAndPersistsOnlyDifferenceMetadata() throws Exception {
        var table = new CopyTable("databridge_source", "verify_items", "business", "verify_items");
        try (var coordinator = new TaskCoordinator(source, target, settings(), 10)) {
            var taskId = coordinator.create(table);
            coordinator.start(taskId);
            awaitStreaming(coordinator, taskId);
            var verifier = new Verifier(source, target);
            var clean = verifier.verify(taskId, Duration.ofSeconds(20), 2);
            assertEquals("CONSISTENT", clean.status());
            assertEquals(3, clean.sourceRows());
            assertEquals(3, clean.targetRows());
            assertEquals(0, clean.differenceRows());
            assertEquals(clean, verifier.report(taskId, clean.runId()));

            try (var pg = target.getConnection(); var statement = pg.createStatement()) {
                statement.execute("UPDATE business.verify_items SET name = 'target-only-secret' WHERE id = 1");
                statement.execute("DELETE FROM business.verify_items WHERE id = 2");
                statement.execute("INSERT INTO business.verify_items(id, name) VALUES (999, 'extra-secret')");
            }
            var changed = verifier.verify(taskId, Duration.ofSeconds(20), 2);
            assertEquals("DIFFERENT", changed.status());
            assertEquals(3, changed.sourceRows());
            assertEquals(3, changed.targetRows());
            assertEquals(3, changed.differenceRows());
            assertEquals(Set.of("VALUE_MISMATCH", "MISSING_TARGET", "EXTRA_TARGET"),
                    changed.differences().stream().map(VerificationRepository.VerificationDiff::kind)
                            .collect(java.util.stream.Collectors.toSet()));
            assertTrue(changed.differences().stream().anyMatch(d -> d.rowId() == 1
                    && d.columns().contains("name")));
            assertEquals(changed, verifier.report(taskId, changed.runId()));
            assertFalse(changed.toString().contains("target-only-secret"));
            assertFalse(changed.toString().contains("extra-secret"));
            coordinator.stop(taskId);
        }
    }

    @Test
    void scansTenThousandRowsAndRejectsAWriteInsideTheProbeWindow() throws Exception {
        try (var db = DriverManager.getConnection(mysql.getJdbcUrl(), "root", "test");
             var statement = db.createStatement()) {
            statement.execute("CREATE TABLE verify_many (id BIGINT PRIMARY KEY, name VARCHAR(100))");
            db.setAutoCommit(false);
            try (var insert = db.prepareStatement("INSERT INTO verify_many VALUES (?, ?)")) {
                for (int id = 1; id <= 10_000; id++) {
                    insert.setLong(1, id);
                    insert.setString(2, "item-" + id);
                    insert.addBatch();
                    if (id % 1_000 == 0) {
                        insert.executeBatch();
                        db.commit();
                    }
                }
            }
        }
        try (var pg = target.getConnection(); var statement = pg.createStatement()) {
            statement.execute("CREATE TABLE business.verify_many (id BIGINT PRIMARY KEY, name VARCHAR(100))");
        }
        var table = new CopyTable("databridge_source", "verify_many", "business", "verify_many");
        try (var coordinator = new TaskCoordinator(source, target, settings(), 250)) {
            var taskId = coordinator.create(table);
            coordinator.start(taskId);
            awaitStreaming(coordinator, taskId);
            var verifier = new Verifier(source, target);
            var heapPools = ManagementFactory.getMemoryPoolMXBeans().stream()
                    .filter(pool -> pool.getType() == MemoryType.HEAP).toList();
            heapPools.forEach(MemoryPoolMXBean::resetPeakUsage);
            long started = System.nanoTime();
            var clean = verifier.verify(taskId, Duration.ofSeconds(30), 500);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            long peakHeapBytes = heapPools.stream().mapToLong(pool -> pool.getPeakUsage().getUsed()).sum();
            assertEquals("CONSISTENT", clean.status());
            assertEquals(10_000, clean.sourceRows());
            assertEquals(10_000, clean.targetRows());
            System.out.println("VERIFICATION_10000_ROWS_MS=" + elapsedMillis
                    + " BATCH_SIZE=500 PEAK_HEAP_BYTES=" + peakHeapBytes);

            int probesBefore;
            try (var db = DriverManager.getConnection(mysql.getJdbcUrl(), "root", "test");
                 var statement = db.createStatement();
                 var rows = statement.executeQuery("SELECT COUNT(*) FROM sync_probe")) {
                rows.next();
                probesBefore = rows.getInt(1);
            }
            var running = CompletableFuture.supplyAsync(
                    () -> verifier.verify(taskId, Duration.ofSeconds(30), 100));
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            boolean firstProbeWritten = false;
            while (System.nanoTime() < deadline) {
                try (var db = DriverManager.getConnection(mysql.getJdbcUrl(), "root", "test");
                     var statement = db.createStatement();
                     var rows = statement.executeQuery("SELECT COUNT(*) FROM sync_probe")) {
                    rows.next();
                    if (rows.getInt(1) > probesBefore) {
                        firstProbeWritten = true;
                        break;
                    }
                }
                Thread.sleep(10);
            }
            assertTrue(firstProbeWritten, "Verification did not write its first probe");
            try (var db = DriverManager.getConnection(mysql.getJdbcUrl(), "root", "test");
                 var statement = db.createStatement()) {
                statement.execute("INSERT INTO verify_many VALUES (10001, 'during-scan-secret')");
            }
            var raced = running.get(60, TimeUnit.SECONDS);
            assertEquals("INCONCLUSIVE", raced.status());
            assertEquals("SOURCE_CHANGED", raced.reasonCode());
            assertFalse(raced.toString().contains("during-scan-secret"));
            coordinator.stop(taskId);
        }
    }

    @Test
    void reportsInconclusiveWhenProbeCannotBeCapturedOrApplied() throws Exception {
        try (var db = DriverManager.getConnection(mysql.getJdbcUrl(), "root", "test");
             var statement = db.createStatement()) {
            statement.execute("CREATE TABLE verify_stalled (id BIGINT PRIMARY KEY, name VARCHAR(100))");
        }
        try (var pg = target.getConnection(); var statement = pg.createStatement()) {
            statement.execute("CREATE TABLE business.verify_stalled "
                    + "(id BIGINT PRIMARY KEY, name VARCHAR(100))");
        }
        try (var coordinator = new TaskCoordinator(source, target, settings(), 10)) {
            var taskId = coordinator.create(new CopyTable(
                    "databridge_source", "verify_stalled", "business", "verify_stalled"));
            try (var pg = target.getConnection(); var update = pg.prepareStatement("""
                    UPDATE databridge_meta.sync_task SET status = 'STREAMING' WHERE task_id = ?
                    """)) {
                update.setObject(1, taskId);
                assertEquals(1, update.executeUpdate());
            }
            var report = new Verifier(source, target).verify(taskId, Duration.ofMillis(300), 10);
            assertEquals("INCONCLUSIVE", report.status());
            assertEquals("BARRIER_TIMEOUT", report.reasonCode());
            assertEquals(null, report.differenceRows());
        }
    }

    private static void awaitStreaming(TaskCoordinator coordinator, java.util.UUID taskId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline) {
            String status = coordinator.task(taskId).status();
            if (status.equals("STREAMING")) return;
            if (status.equals("FAILED")) throw new AssertionError(coordinator.task(taskId).errorMessage());
            Thread.sleep(100);
        }
        throw new AssertionError("Task did not reach STREAMING");
    }

    private static Properties settings() {
        var settings = new Properties();
        settings.setProperty("database.hostname", mysql.getHost());
        settings.setProperty("database.port", Integer.toString(mysql.getMappedPort(3306)));
        settings.setProperty("database.user", "capture");
        settings.setProperty("database.password", "capturepass");
        settings.setProperty("database.server.id", "5431");
        settings.setProperty("database.include.list", "databridge_source");
        settings.setProperty("offset.storage.jdbc.connection.url", postgres.getJdbcUrl());
        settings.setProperty("offset.storage.jdbc.connection.user", postgres.getUsername());
        settings.setProperty("offset.storage.jdbc.connection.password", postgres.getPassword());
        settings.setProperty("schema.history.internal.jdbc.connection.url", postgres.getJdbcUrl());
        settings.setProperty("schema.history.internal.jdbc.connection.user", postgres.getUsername());
        settings.setProperty("schema.history.internal.jdbc.connection.password", postgres.getPassword());
        return settings;
    }
}
