package com.databridge.fault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.databridge.copy.CopyTable;
import com.databridge.task.TaskRepository;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Explicit process-kill benchmark: each trial is a new task, not a simulated exception. */
@Testcontainers
@EnabledIfSystemProperty(named = "databridge.fault", matches = "true")
class FaultRecoveryBenchmarkIT {
    private static final long CONTAINER_MEMORY = 2L * 1024 * 1024 * 1024;
    private static final long CONTAINER_NANO_CPUS = 2_000_000_000L;
    private static final Duration WINDOW_TIMEOUT = Duration.ofSeconds(75);
    private static final Duration RECOVERY_TIMEOUT = Duration.ofSeconds(100);

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4.11")
            .withDatabaseName("databridge_source")
            .withCommand("--server-id=5540", "--log-bin=mysql-bin",
                    "--binlog-format=ROW", "--binlog-row-image=FULL")
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                    .withMemory(CONTAINER_MEMORY).withNanoCPUs(CONTAINER_NANO_CPUS));
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11")
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                    .withMemory(CONTAINER_MEMORY).withNanoCPUs(CONTAINER_NANO_CPUS));

    @Test
    void forceKillsAndRecoversAllThreeWindows() throws Exception {
        int repetitions = Boolean.getBoolean("databridge.fault.smoke") ? 1 : 3;
        String output = System.getProperty("databridge.fault.csv");
        if (output == null || output.isBlank()) throw new IllegalArgumentException("databridge.fault.csv required");
        Path csv = Path.of(output).toAbsolutePath();
        Files.createDirectories(csv.getParent());
        var target = target();
        Flyway.configure().dataSource(target).load().migrate();
        prepareDatabases(target);
        var source = new DriverManagerDataSource(mysql.getJdbcUrl(), "capture", "capturepass");
        var tasks = new TaskRepository(source, target);
        try (BufferedWriter writer = Files.newBufferedWriter(csv)) {
            writer.write("scenario,repetition,task_id,utc,kill_status,checkpoint_last_id,"
                    + "event_sequence,cursor_before,blocked_backends,recovery_ms,"
                    + "source_rows,target_rows,difference_rows,final_status");
            writer.newLine();
            writer.flush();
            for (String scenario : new String[] {"full_copy", "cdc_persist", "event_replay"}) {
                for (int repetition = 1; repetition <= repetitions; repetition++) {
                    writer.write(runTrial(target, tasks, scenario, repetition, csv.getParent()));
                    writer.newLine();
                    writer.flush();
                    System.out.println("FAULT_RECOVERY scenario=" + scenario + " repetition=" + repetition);
                }
            }
        }
        assertEquals(1 + 3 * repetitions, Files.readAllLines(csv).size());
    }

    private static String runTrial(PGSimpleDataSource target, TaskRepository tasks,
                                   String scenario, int repetition, Path logs) throws Exception {
        String table = "fault_" + scenario + "_" + repetition;
        int rowCount = scenario.equals("full_copy") ? 1000 : 20;
        prepareTable(target, table, rowCount);
        UUID taskId = tasks.create(new CopyTable("databridge_source", table, "business", table));
        Process initial = startChild(taskId, scenario.equals("full_copy") ? 10 : 20,
                logs.resolve(table + "-initial.log"));
        Process resumed = null;
        long checkpoint = 0;
        long event = 0;
        long cursor = 0;
        int blocked = 0;
        String killStatus;
        try {
            if (scenario.equals("full_copy")) {
                waitUntil(WINDOW_TIMEOUT, initial, () -> {
                    var state = tasks.get(taskId);
                    long progress = checkpoint(target, taskId);
                    return state.status().equals("COPYING") && progress >= 10 && progress < rowCount;
                }, "full-copy checkpoint did not advance during COPYING");
                checkpoint = checkpoint(target, taskId);
                killStatus = tasks.get(taskId).status();
                assertEquals("COPYING", killStatus);
                assertTrue(checkpoint > 0 && checkpoint < rowCount);
                kill(initial);
            } else {
                waitUntil(WINDOW_TIMEOUT, initial,
                        () -> tasks.get(taskId).status().equals("STREAMING") && cursorExists(target, taskId),
                        "task did not reach STREAMING with cursor");
                try (var lock = target.getConnection()) {
                    lock.setAutoCommit(false);
                    int lockerPid;
                    if (scenario.equals("cdc_persist")) {
                        try (var statement = lock.prepareStatement("SELECT last_sequence "
                                + "FROM databridge_meta.event_cursor WHERE task_id = ? FOR UPDATE")) {
                            statement.setObject(1, taskId);
                            try (var row = statement.executeQuery()) {
                                assertTrue(row.next());
                                cursor = row.getLong(1);
                            }
                        }
                        lockerPid = 0;
                    } else {
                        try (var statement = lock.prepareStatement(
                                "SELECT id FROM business." + table + " WHERE id = 1 FOR UPDATE")) {
                            try (var row = statement.executeQuery()) { assertTrue(row.next()); }
                        }
                        try (var statement = lock.createStatement();
                             var row = statement.executeQuery("SELECT pg_backend_pid()")) {
                            row.next();
                            lockerPid = row.getInt(1);
                        }
                        cursor = cursor(target, taskId);
                    }
                    String marker = "recovered-" + scenario + "-" + repetition;
                    updateSource(table, marker);
                    waitUntil(WINDOW_TIMEOUT, initial,
                            () -> eventSequence(target, taskId, table, marker) > 0,
                            "CDC business event was not persisted");
                    event = eventSequence(target, taskId, table, marker);
                    if (scenario.equals("event_replay")) {
                        int pid = lockerPid;
                        waitUntil(WINDOW_TIMEOUT, initial, () -> blockedBackends(target, pid) > 0,
                                "event applier did not block on target row lock");
                        blocked = blockedBackends(target, lockerPid);
                    }
                    killStatus = tasks.get(taskId).status();
                    assertEquals("STREAMING", killStatus);
                    assertTrue(event > cursor, "event must be committed ahead of durable cursor");
                    kill(initial);
                    lock.rollback();
                }
            }
            Instant utc = Instant.now();
            long start = System.nanoTime();
            resumed = startChild(taskId, scenario.equals("full_copy") ? 10 : 20,
                    logs.resolve(table + "-resumed.log"));
            long expectedEvent = event;
            Process recoveredProcess = resumed;
            waitUntil(RECOVERY_TIMEOUT, recoveredProcess,
                    () -> tasks.get(taskId).status().equals("STREAMING")
                            && rows(target, table).difference() == 0
                            && (expectedEvent == 0 || cursor(target, taskId) >= expectedEvent),
                    "task did not resume and converge");
            double elapsedMs = (System.nanoTime() - start) / 1_000_000.0;
            Rows compared = rows(target, table);
            String finalStatus = tasks.get(taskId).status();
            assertEquals(0, compared.difference());
            assertEquals("STREAMING", finalStatus);
            return String.join(",", scenario, Integer.toString(repetition), taskId.toString(), utc.toString(),
                    killStatus, Long.toString(checkpoint), Long.toString(event), Long.toString(cursor),
                    Integer.toString(blocked), String.format(java.util.Locale.ROOT, "%.3f", elapsedMs),
                    Integer.toString(compared.source()), Integer.toString(compared.target()),
                    Integer.toString(compared.difference()), finalStatus);
        } finally {
            if (initial.isAlive()) kill(initial);
            if (resumed != null && resumed.isAlive()) kill(resumed);
        }
    }

    private static Process startChild(UUID taskId, int batchSize, Path log) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        var builder = new ProcessBuilder(java, "-Xmx512m", "-cp", System.getProperty("java.class.path"),
                FaultRecoveryChild.class.getName(), taskId.toString(), Integer.toString(batchSize));
        var env = builder.environment();
        env.put("FAULT_SOURCE_URL", mysql.getJdbcUrl());
        env.put("FAULT_SOURCE_USER", "capture");
        env.put("FAULT_SOURCE_PASSWORD", "capturepass");
        env.put("FAULT_TARGET_URL", postgres.getJdbcUrl());
        env.put("FAULT_TARGET_USER", postgres.getUsername());
        env.put("FAULT_TARGET_PASSWORD", postgres.getPassword());
        env.put("FAULT_MYSQL_HOST", mysql.getHost());
        env.put("FAULT_MYSQL_PORT", Integer.toString(mysql.getMappedPort(3306)));
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        return builder.start();
    }

    private static void kill(Process process) throws Exception {
        assertTrue(process.isAlive(), "fault child exited before kill");
        process.destroyForcibly();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "fault child did not exit after force kill");
        assertTrue(process.exitValue() != 0, "force-killed child exited cleanly");
    }

    private static void waitUntil(Duration timeout, Process process, CheckedCondition condition,
                                  String failure) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) throw new AssertionError(failure + ": child exited " + process.exitValue());
            if (condition.test()) return;
            Thread.sleep(25);
        }
        throw new AssertionError(failure + " within " + timeout);
    }

    private static long checkpoint(PGSimpleDataSource target, UUID taskId) throws SQLException {
        try (var db = target.getConnection();
             var statement = db.prepareStatement("SELECT last_id FROM databridge_meta.full_checkpoint "
                     + "WHERE task_id = ? AND NOT completed")) {
            statement.setObject(1, taskId);
            try (var row = statement.executeQuery()) { return row.next() ? row.getLong(1) : 0; }
        }
    }

    private static boolean cursorExists(PGSimpleDataSource target, UUID taskId) throws SQLException {
        try (var db = target.getConnection();
             var statement = db.prepareStatement("SELECT 1 FROM databridge_meta.event_cursor WHERE task_id = ?")) {
            statement.setObject(1, taskId);
            try (var row = statement.executeQuery()) { return row.next(); }
        }
    }

    private static long cursor(PGSimpleDataSource target, UUID taskId) throws SQLException {
        try (var db = target.getConnection();
             var statement = db.prepareStatement(
                     "SELECT last_sequence FROM databridge_meta.event_cursor WHERE task_id = ?")) {
            statement.setObject(1, taskId);
            try (var row = statement.executeQuery()) { return row.next() ? row.getLong(1) : 0; }
        }
    }

    private static long eventSequence(PGSimpleDataSource target, UUID taskId,
                                      String table, String marker) throws SQLException {
        try (var db = target.getConnection();
             var statement = db.prepareStatement("SELECT sequence FROM databridge_meta.cdc_event "
                     + "WHERE task_id = ? AND table_name = ? AND row_id = 1 "
                     + "AND after_row ->> 'marker' = ? ORDER BY sequence DESC LIMIT 1")) {
            statement.setObject(1, taskId);
            statement.setString(2, "databridge_source." + table);
            statement.setString(3, marker);
            try (var row = statement.executeQuery()) { return row.next() ? row.getLong(1) : 0; }
        }
    }

    private static int blockedBackends(PGSimpleDataSource target, int lockerPid) throws SQLException {
        try (var db = target.getConnection();
             var statement = db.prepareStatement("SELECT count(*) FROM pg_stat_activity "
                     + "WHERE ? = ANY(pg_blocking_pids(pid))")) {
            statement.setInt(1, lockerPid);
            try (var row = statement.executeQuery()) { row.next(); return row.getInt(1); }
        }
    }

    private static Rows rows(PGSimpleDataSource target, String table) throws SQLException {
        Map<Long, String> source = new HashMap<>();
        try (var db = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
             var statement = db.createStatement();
             var row = statement.executeQuery("SELECT id, marker FROM " + table)) {
            while (row.next()) source.put(row.getLong(1), row.getString(2));
        }
        Map<Long, String> destination = new HashMap<>();
        try (var db = target.getConnection(); var statement = db.createStatement();
             var row = statement.executeQuery("SELECT id, marker FROM business." + table)) {
            while (row.next()) destination.put(row.getLong(1), row.getString(2));
        }
        int difference = 0;
        for (var id : source.keySet()) {
            if (!java.util.Objects.equals(source.get(id), destination.get(id))) difference++;
        }
        for (var id : destination.keySet()) {
            if (!source.containsKey(id)) difference++;
        }
        return new Rows(source.size(), destination.size(), difference);
    }

    private static void updateSource(String table, String marker) throws SQLException {
        try (var db = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
             var statement = db.prepareStatement("UPDATE " + table + " SET marker = ? WHERE id = 1")) {
            statement.setString(1, marker);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void prepareTable(PGSimpleDataSource target, String table, int rows) throws SQLException {
        try (var pg = target.getConnection(); var statement = pg.createStatement()) {
            statement.execute("CREATE TABLE business." + table + " (id BIGINT PRIMARY KEY, marker VARCHAR(64))");
        }
        try (var mysqlDb = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
             var statement = mysqlDb.createStatement()) {
            statement.execute("CREATE TABLE " + table + " (id BIGINT PRIMARY KEY, marker VARCHAR(64))");
            try (var insert = mysqlDb.prepareStatement("INSERT INTO " + table + " VALUES (?, 'initial')")) {
                for (int id = 1; id <= rows; id++) { insert.setInt(1, id); insert.addBatch(); }
                assertEquals(rows, insert.executeBatch().length);
            }
        }
    }

    private static void prepareDatabases(PGSimpleDataSource target) throws SQLException {
        try (var pg = target.getConnection(); var statement = pg.createStatement()) {
            statement.execute("CREATE SCHEMA business");
        }
        try (var db = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
             var statement = db.createStatement()) {
            statement.execute("CREATE TABLE sync_probe (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                    + "marker CHAR(36) NOT NULL UNIQUE)");
            statement.execute("CREATE USER 'capture'@'%' IDENTIFIED BY 'capturepass'");
            statement.execute("GRANT SELECT ON databridge_source.* TO 'capture'@'%'");
            statement.execute("GRANT INSERT ON databridge_source.sync_probe TO 'capture'@'%'");
            statement.execute("GRANT RELOAD, SHOW DATABASES, REPLICATION SLAVE, "
                    + "REPLICATION CLIENT ON *.* TO 'capture'@'%'");
        }
    }

    private static PGSimpleDataSource target() {
        var target = new PGSimpleDataSource();
        target.setURL(postgres.getJdbcUrl());
        target.setUser(postgres.getUsername());
        target.setPassword(postgres.getPassword());
        return target;
    }

    @FunctionalInterface
    private interface CheckedCondition { boolean test() throws Exception; }
    private record Rows(int source, int target, int difference) { }
}
