package com.databridge.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.databridge.copy.CopyTable;
import com.databridge.task.TaskCoordinator;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Explicit benchmark of commit-call-to-first-target-observation, including polling error. */
@Testcontainers
@EnabledIfSystemProperty(named = "databridge.latency", matches = "true")
class CdcLatencyBenchmarkIT {
    private static final long CONTAINER_MEMORY = 2L * 1024 * 1024 * 1024;
    private static final long CONTAINER_NANO_CPUS = 2_000_000_000L;
    private static final long WRITE_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    private static final long POLL_INTERVAL_MILLIS = 10;

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4.11")
            .withDatabaseName("databridge_source")
            .withCommand("--server-id=5440", "--log-bin=mysql-bin",
                    "--binlog-format=ROW", "--binlog-row-image=FULL")
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                    .withMemory(CONTAINER_MEMORY).withNanoCPUs(CONTAINER_NANO_CPUS));
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11")
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                    .withMemory(CONTAINER_MEMORY).withNanoCPUs(CONTAINER_NANO_CPUS));

    @Test
    void measuresThreeStableUpdateRunsThroughRealCdc() throws Exception {
        boolean smoke = Boolean.getBoolean("databridge.latency.smoke");
        int repetitions = smoke ? 1 : 3;
        int samples = smoke ? 5 : 100;
        String output = System.getProperty("databridge.latency.csv");
        if (output == null || output.isBlank()) {
            throw new IllegalArgumentException("databridge.latency.csv is required");
        }
        Path csv = Path.of(output);
        Files.createDirectories(csv.toAbsolutePath().getParent());
        var target = target();
        Flyway.configure().dataSource(target).load().migrate();
        prepareDatabases(target);
        var source = new DriverManagerDataSource(mysql.getJdbcUrl(), "capture", "capturepass");
        try (var coordinator = new TaskCoordinator(source, target, settings(), 50);
             BufferedWriter writer = Files.newBufferedWriter(csv)) {
            UUID taskId = coordinator.create(new CopyTable(
                    "databridge_source", "latency_items", "business", "latency_items"));
            coordinator.start(taskId);
            awaitStreaming(coordinator, taskId);
            writer.write("utc,repetition,sample,row_id,latency_ms,commit_ms,schedule_lag_ms");
            writer.newLine();
            writer.flush();
            for (int repetition = 1; repetition <= repetitions; repetition++) {
                Samples result = measureRun(target, coordinator, taskId, repetition, samples);
                for (int index = 0; index < samples; index++) {
                    writer.write(String.join(",", result.utc().get(index),
                            Integer.toString(repetition), Integer.toString(index + 1),
                            Integer.toString(index + 1), millis(result.latency().get(index)),
                            millis(result.commit().get(index)), millis(result.scheduleLag().get(index))));
                    writer.newLine();
                }
                writer.flush();
                System.out.println("CDC_LATENCY repetition=" + repetition + " samples=" + samples);
            }
            coordinator.stop(taskId);
        }
        assertEquals(1 + repetitions * samples, Files.readAllLines(csv).size());
    }

    private static Samples measureRun(PGSimpleDataSource target, TaskCoordinator coordinator,
                                       UUID taskId, int repetition, int samples) throws Exception {
        var starts = new AtomicLongArray(samples);
        var commit = new AtomicLongArray(samples);
        var scheduleLag = new AtomicLongArray(samples);
        var latency = new AtomicLongArray(samples);
        var utc = new AtomicReferenceArray<String>(samples);
        long firstSchedule = System.nanoTime() + WRITE_INTERVAL_NANOS;
        try (var executor = Executors.newSingleThreadExecutor()) {
            var writes = executor.submit(() -> writeUpdates(
                    repetition, samples, firstSchedule, starts, commit, scheduleLag, utc));
            int observed = 0;
            long deadline = firstSchedule + Duration.ofSeconds(smokeTimeout(samples)).toNanos();
            try (var pg = target.getConnection();
                 var read = pg.prepareStatement("SELECT id, marker FROM business.latency_items")) {
                while (observed < samples && System.nanoTime() < deadline) {
                    if (writes.isDone()) {
                        awaitWrites(writes);
                    }
                    try (var rows = read.executeQuery()) {
                        long observedAt = System.nanoTime();
                        while (rows.next()) {
                            int id = rows.getInt(1);
                            if (id < 1 || id > samples) continue;
                            int index = id - 1;
                            if (!marker(repetition, id).equals(rows.getString(2))) continue;
                            long start = starts.get(index);
                            if (start > 0 && latency.compareAndSet(index, 0, observedAt - start)) {
                                observed++;
                            }
                        }
                    }
                    if (observed < samples) Thread.sleep(POLL_INTERVAL_MILLIS);
                }
            }
            awaitWrites(writes);
            assertEquals(samples, observed, "Not all CDC updates reached target before timeout");
            assertEquals("STREAMING", coordinator.task(taskId).status());
        }
        return new Samples(utc, latency, commit, scheduleLag);
    }

    private static int smokeTimeout(int samples) {
        return samples == 5 ? 20 : 30;
    }

    private static void writeUpdates(int repetition, int samples, long firstSchedule,
                                     AtomicLongArray starts, AtomicLongArray commit,
                                     AtomicLongArray scheduleLag, AtomicReferenceArray<String> utc) {
        try (var db = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
             var update = db.prepareStatement("UPDATE latency_items SET marker = ? WHERE id = ?")) {
            db.setAutoCommit(false);
            for (int index = 0; index < samples; index++) {
                long scheduled = firstSchedule + index * WRITE_INTERVAL_NANOS;
                while (System.nanoTime() < scheduled) {
                    TimeUnit.MILLISECONDS.sleep(1);
                }
                update.setString(1, marker(repetition, index + 1));
                update.setInt(2, index + 1);
                if (update.executeUpdate() != 1) {
                    throw new IllegalStateException("Source row update failed: " + (index + 1));
                }
                utc.set(index, Instant.now().toString());
                long beforeCommit = System.nanoTime();
                db.commit();
                long afterCommit = System.nanoTime();
                starts.set(index, beforeCommit);
                commit.set(index, afterCommit - beforeCommit);
                scheduleLag.set(index, Math.max(0, beforeCommit - scheduled));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Latency writer failed", e);
        }
    }

    private static void awaitWrites(java.util.concurrent.Future<?> writes) throws Exception {
        try {
            writes.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Latency source writes failed", e.getCause());
        }
    }

    private static String marker(int repetition, int sample) {
        return "latency-" + repetition + "-" + sample;
    }

    private static String millis(long nanos) {
        assertTrue(nanos >= 0);
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }

    private static PGSimpleDataSource target() {
        var target = new PGSimpleDataSource();
        target.setURL(postgres.getJdbcUrl());
        target.setUser(postgres.getUsername());
        target.setPassword(postgres.getPassword());
        return target;
    }

    private static void prepareDatabases(PGSimpleDataSource target) throws Exception {
        try (var pg = target.getConnection(); var statement = pg.createStatement()) {
            statement.execute("CREATE SCHEMA business");
            statement.execute("CREATE TABLE business.latency_items "
                    + "(id BIGINT PRIMARY KEY, marker VARCHAR(64))");
        }
        try (var db = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
             var statement = db.createStatement()) {
            statement.execute("CREATE TABLE latency_items "
                    + "(id BIGINT PRIMARY KEY, marker VARCHAR(64))");
            statement.execute("CREATE TABLE sync_probe (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                    + "marker CHAR(36) NOT NULL UNIQUE)");
            statement.execute("CREATE USER 'capture'@'%' IDENTIFIED BY 'capturepass'");
            statement.execute("GRANT SELECT ON databridge_source.* TO 'capture'@'%'");
            statement.execute("GRANT INSERT ON databridge_source.sync_probe TO 'capture'@'%'");
            statement.execute("GRANT RELOAD, SHOW DATABASES, REPLICATION SLAVE, "
                    + "REPLICATION CLIENT ON *.* TO 'capture'@'%'");
            try (var insert = db.prepareStatement("INSERT INTO latency_items VALUES (?, 'initial')")) {
                for (int id = 1; id <= 100; id++) {
                    insert.setInt(1, id);
                    insert.addBatch();
                }
                assertEquals(100, insert.executeBatch().length);
            }
        }
    }

    private static void awaitStreaming(TaskCoordinator coordinator, UUID taskId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            String status = coordinator.task(taskId).status();
            if (status.equals("STREAMING")) return;
            if (status.equals("FAILED")) {
                throw new AssertionError("Task failed before latency test: "
                        + coordinator.task(taskId).errorMessage());
            }
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
        settings.setProperty("database.server.id", "5441");
        settings.setProperty("database.include.list", "databridge_source");
        settings.setProperty("offset.storage.jdbc.connection.url", postgres.getJdbcUrl());
        settings.setProperty("offset.storage.jdbc.connection.user", postgres.getUsername());
        settings.setProperty("offset.storage.jdbc.connection.password", postgres.getPassword());
        settings.setProperty("schema.history.internal.jdbc.connection.url", postgres.getJdbcUrl());
        settings.setProperty("schema.history.internal.jdbc.connection.user", postgres.getUsername());
        settings.setProperty("schema.history.internal.jdbc.connection.password", postgres.getPassword());
        return settings;
    }

    private record Samples(AtomicReferenceArray<String> utc, AtomicLongArray latency,
                           AtomicLongArray commit, AtomicLongArray scheduleLag) { }
}
