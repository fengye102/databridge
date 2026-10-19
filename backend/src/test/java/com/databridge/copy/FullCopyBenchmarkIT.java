package com.databridge.copy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Explicit, isolated benchmark; not part of the ordinary regression suite. */
@Testcontainers
@EnabledIfSystemProperty(named = "databridge.benchmark", matches = "true")
class FullCopyBenchmarkIT {
    private static final long CONTAINER_MEMORY = 2L * 1024 * 1024 * 1024;
    private static final long CONTAINER_NANO_CPUS = 2_000_000_000L;
    private static final int SEED = 20261020;

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4.11")
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                    .withMemory(CONTAINER_MEMORY).withNanoCPUs(CONTAINER_NANO_CPUS));
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11")
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                    .withMemory(CONTAINER_MEMORY).withNanoCPUs(CONTAINER_NANO_CPUS));

    @Test
    void measuresCompletedCopyForEveryRandomizedCondition() throws Exception {
        boolean smoke = Boolean.getBoolean("databridge.benchmark.smoke");
        boolean pilot = Boolean.getBoolean("databridge.benchmark.pilot");
        int[] rowCounts = smoke ? new int[] {100}
                : pilot ? new int[] {10_000} : new int[] {10_000, 100_000, 1_000_000};
        int[] batchSizes = smoke ? new int[] {25, 50} : new int[] {500, 1_000};
        int repetitions = smoke || pilot ? 1 : 3;
        Path csv = Path.of(System.getProperty("databridge.benchmark.csv"));
        Files.createDirectories(csv.toAbsolutePath().getParent());
        var source = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        var target = new DriverManagerDataSource(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(target).load().migrate();
        prepareTarget(target.getConnection());
        prepareSources(source.getConnection(), rowCounts);

        // Warm the JVM and both databases; this trial is checked but not recorded.
        runCopy(source, target, rowCounts[0], batchSizes[0]);

        List<Condition> order = new ArrayList<>();
        for (int rows : rowCounts) {
            for (int batch : batchSizes) {
                for (int repeat = 1; repeat <= repetitions; repeat++) {
                    order.add(new Condition(rows, batch, repeat));
                }
            }
        }
        Collections.shuffle(order, new Random(SEED));
        var heapPools = ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == MemoryType.HEAP).toList();
        var worker = new FullCopyWorker(source, target);
        try (BufferedWriter output = Files.newBufferedWriter(csv)) {
            output.write("utc,order,rows,batch_size,repetition,elapsed_ms,rows_per_second,"
                    + "peak_heap_bytes,jvm_max_heap_bytes,seed,mysql_image,postgres_image,"
                    + "container_memory_bytes,container_nano_cpus,java_version,os_name");
            output.newLine();
            output.flush();
            for (int index = 0; index < order.size(); index++) {
                Condition condition = order.get(index);
                truncateTarget(target.getConnection());
                heapPools.forEach(MemoryPoolMXBean::resetPeakUsage);
                long started = System.nanoTime();
                int copied = copyAll(worker, target, sourceTable(condition.rows()), condition.batchSize());
                long elapsedNanos = System.nanoTime() - started;
                long peak = heapPools.stream().mapToLong(pool -> pool.getPeakUsage().getUsed()).sum();
                assertEquals(condition.rows(), copied);
                assertEquals(condition.rows(), targetCount(target.getConnection()));
                long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
                double throughput = condition.rows() / (elapsedNanos / 1_000_000_000.0);
                output.write(String.join(",",
                        Instant.now().toString(), Integer.toString(index + 1),
                        Integer.toString(condition.rows()), Integer.toString(condition.batchSize()),
                        Integer.toString(condition.repetition()), Long.toString(elapsedMillis),
                        String.format(java.util.Locale.ROOT, "%.2f", throughput), Long.toString(peak),
                        Long.toString(Runtime.getRuntime().maxMemory()), Integer.toString(SEED),
                        "mysql:8.4.11", "postgres:17.11", Long.toString(CONTAINER_MEMORY),
                        Long.toString(CONTAINER_NANO_CPUS), System.getProperty("java.version"),
                        System.getProperty("os.name").replace(',', ' ')));
                output.newLine();
                output.flush();
                System.out.println("COPY_BENCHMARK " + (index + 1) + "/" + order.size()
                        + " rows=" + condition.rows() + " batch=" + condition.batchSize()
                        + " repetition=" + condition.repetition() + " elapsed_ms=" + elapsedMillis);
            }
        }
        assertEquals(1 + order.size(), Files.readAllLines(csv).size());
    }

    private static void prepareTarget(Connection connection) throws Exception {
        try (connection; var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA business");
            statement.execute("CREATE TABLE business.bench_items (id BIGINT PRIMARY KEY, "
                    + "name VARCHAR(64), amount NUMERIC(12,2))");
        }
    }

    private static void prepareSources(Connection connection, int[] rowCounts) throws Exception {
        try (connection; var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE bench_digit (n INTEGER PRIMARY KEY)");
            statement.execute("INSERT INTO bench_digit VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9)");
            for (int rows : rowCounts) {
                String table = sourceTable(rows);
                statement.execute("CREATE TABLE " + table
                        + " (id BIGINT PRIMARY KEY, name VARCHAR(64), amount DECIMAL(12,2))");
                statement.execute("INSERT INTO " + table + " (id,name,amount) "
                        + "SELECT n+1, CONCAT('row-',LPAD(n+1,7,'0')), CAST((n % 10000)/100 AS DECIMAL(12,2)) "
                        + "FROM (SELECT a.n+10*b.n+100*c.n+1000*d.n+10000*e.n+100000*f.n AS n "
                        + "FROM bench_digit a CROSS JOIN bench_digit b CROSS JOIN bench_digit c "
                        + "CROSS JOIN bench_digit d CROSS JOIN bench_digit e CROSS JOIN bench_digit f) digits "
                        + "WHERE n < " + rows + " ORDER BY n");
                try (var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    assertTrue(result.next());
                    assertEquals(rows, result.getInt(1));
                }
            }
        }
    }

    private static void truncateTarget(Connection connection) throws Exception {
        try (connection; var statement = connection.createStatement()) {
            statement.execute("TRUNCATE business.bench_items");
        }
    }

    private static int targetCount(Connection connection) throws Exception {
        try (connection; var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM business.bench_items")) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static void runCopy(DriverManagerDataSource source, DriverManagerDataSource target,
                                int rows, int batchSize) throws Exception {
        truncateTarget(target.getConnection());
        assertEquals(rows, copyAll(new FullCopyWorker(source, target), target, sourceTable(rows), batchSize));
        assertEquals(rows, targetCount(target.getConnection()));
    }

    private static int copyAll(FullCopyWorker worker, DriverManagerDataSource target,
                               String sourceTable, int batchSize) throws Exception {
        UUID taskId = UUID.randomUUID();
        CopyTable table = new CopyTable(mysql.getDatabaseName(), sourceTable, "business", "bench_items");
        int copied = 0;
        int next;
        while ((next = worker.copyNextBatch(taskId, table, batchSize)) > 0) {
            copied += next;
        }
        try (var connection = target.getConnection(); var query = connection.prepareStatement(
                "SELECT completed, last_id FROM databridge_meta.full_checkpoint WHERE task_id = ?")) {
            query.setObject(1, taskId);
            try (var result = query.executeQuery()) {
                assertTrue(result.next());
                assertTrue(result.getBoolean(1));
                assertEquals(copied, result.getLong(2));
            }
        }
        return copied;
    }

    private static String sourceTable(int rows) {
        return "bench_items_" + rows;
    }

    private record Condition(int rows, int batchSize, int repetition) { }
}
