package com.databridge.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.databridge.copy.CopyTable;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class TaskPreflightIT {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4.11")
            .withDatabaseName("databridge_source");
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11");
    static PGSimpleDataSource target;
    static DriverManagerDataSource source;

    @BeforeAll
    static void setup() {
        target = new PGSimpleDataSource();
        target.setURL(postgres.getJdbcUrl());
        target.setUser(postgres.getUsername());
        target.setPassword(postgres.getPassword());
        source = new DriverManagerDataSource(mysql.getJdbcUrl(), "root", "test");
        Flyway.configure().dataSource(target).load().migrate();
    }

    @BeforeEach
    void reset() throws Exception {
        executeSource("DROP TABLE IF EXISTS orders, no_pk, composite_pk, int_key, "
                + "unsigned_key, blob_items, sync_probe");
        executeSource("CREATE TABLE orders (id BIGINT PRIMARY KEY, name VARCHAR(100), "
                + "amount DECIMAL(18,4), observed_at DATETIME(6))");
        executeSource("CREATE TABLE sync_probe (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "marker CHAR(36) NOT NULL UNIQUE)");
        executeTarget("TRUNCATE databridge_meta.verification_diff, "
                + "databridge_meta.verification_run, databridge_meta.sync_task");
        executeTarget("DROP SCHEMA IF EXISTS business CASCADE");
        executeTarget("CREATE SCHEMA business");
        executeTarget("CREATE TABLE business.orders (id BIGINT PRIMARY KEY, name VARCHAR(100), "
                + "amount NUMERIC(18,4), observed_at TIMESTAMP(6))");
    }

    @Test
    void createsValidTaskAndExcludesSecondTaskForSameTarget() throws Exception {
        var repository = new TaskRepository(source, target);
        repository.create(table("orders", "orders"));
        assertEquals(1, taskCount());
        assertEquals("CREATED", taskStatus());
        assertThrows(IllegalArgumentException.class,
                () -> repository.create(table("orders", "orders")));
        assertEquals(1, taskCount());
    }

    @Test
    void rejectsNonEmptyTargetWithoutPersistingTask() throws Exception {
        executeTarget("INSERT INTO business.orders(id) VALUES (1)");
        assertRejected(table("orders", "orders"));
    }

    @Test
    void rejectsMissingCompositeAndNonBigintSourceKeys() throws Exception {
        executeSource("CREATE TABLE no_pk (id BIGINT, name VARCHAR(100))");
        executeSource("CREATE TABLE composite_pk (id BIGINT, name VARCHAR(100), "
                + "PRIMARY KEY(id, name))");
        executeSource("CREATE TABLE int_key (id INT PRIMARY KEY, name VARCHAR(100))");
        executeSource("CREATE TABLE unsigned_key (id BIGINT UNSIGNED PRIMARY KEY, name VARCHAR(100))");
        assertRejected(table("no_pk", "orders"));
        assertRejected(table("composite_pk", "orders"));
        assertRejected(table("int_key", "orders"));
        assertRejected(table("unsigned_key", "orders"));
    }

    @Test
    void rejectsMissingTargetKeyAndNarrowTypes() throws Exception {
        executeTarget("DROP TABLE business.orders");
        executeTarget("CREATE TABLE business.orders (id BIGINT, name VARCHAR(100), "
                + "amount NUMERIC(18,4), observed_at TIMESTAMP(6))");
        assertRejected(table("orders", "orders"));
        executeTarget("DROP TABLE business.orders");
        executeTarget("CREATE TABLE business.orders (id BIGINT PRIMARY KEY, name VARCHAR(20), "
                + "amount NUMERIC(18,4), observed_at TIMESTAMP(6))");
        assertRejected(table("orders", "orders"));
        executeTarget("DROP TABLE business.orders");
        executeTarget("CREATE TABLE business.orders (id BIGINT PRIMARY KEY, name VARCHAR(100), "
                + "amount NUMERIC(10,2), observed_at TIMESTAMP(6))");
        assertRejected(table("orders", "orders"));
        executeTarget("DROP TABLE business.orders");
        executeTarget("CREATE TABLE business.orders (id BIGINT PRIMARY KEY, name VARCHAR(100), "
                + "amount NUMERIC(18,4), observed_at TIMESTAMP(3))");
        assertRejected(table("orders", "orders"));
    }

    @Test
    void rejectsUnsupportedColumnAndMissingProbeTable() throws Exception {
        executeSource("CREATE TABLE blob_items (id BIGINT PRIMARY KEY, payload BLOB)");
        executeTarget("CREATE TABLE business.blob_items (id BIGINT PRIMARY KEY, payload BYTEA)");
        assertRejected(table("blob_items", "blob_items"));
        executeSource("DROP TABLE sync_probe");
        assertRejected(table("orders", "orders"));
    }

    @Test
    void rejectsProbeWithoutAutoIncrementPrimaryKey() throws Exception {
        executeSource("DROP TABLE sync_probe");
        executeSource("CREATE TABLE sync_probe (id BIGINT PRIMARY KEY, marker CHAR(36) NOT NULL)");
        assertRejected(table("orders", "orders"));
    }

    @Test
    void failureReasonDoesNotPersistCredentialText() throws Exception {
        var repository = new TaskRepository(source, target);
        var taskId = repository.create(table("orders", "orders"));
        repository.start(taskId);
        repository.fail(taskId, new IllegalStateException("password=topsecret"));
        assertEquals("FAILED", repository.get(taskId).status());
        assertFalse(repository.get(taskId).errorMessage().contains("topsecret"));
    }

    @Test
    void repeatedCaptureFailurePreservesOriginalCopyResumeStage() throws Exception {
        var repository = new TaskRepository(source, target);
        var taskId = repository.create(table("orders", "orders"));
        repository.start(taskId);
        repository.advance(taskId, "CAPTURING", "COPYING");
        repository.fail(taskId, new IllegalStateException("copy failed"));
        assertEquals("COPYING", repository.get(taskId).resumeStatus());
        repository.start(taskId);
        repository.fail(taskId, new IllegalStateException("capture failed on restart"));
        assertEquals("COPYING", repository.get(taskId).resumeStatus());
    }

    private static void assertRejected(CopyTable table) throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new TaskRepository(source, target).create(table));
        assertEquals(0, taskCount());
    }

    private static CopyTable table(String sourceTable, String targetTable) {
        return new CopyTable("databridge_source", sourceTable, "business", targetTable);
    }

    private static int taskCount() throws Exception {
        try (var connection = target.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM databridge_meta.sync_task")) {
            result.next();
            return result.getInt(1);
        }
    }

    private static String taskStatus() throws Exception {
        try (var connection = target.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT status FROM databridge_meta.sync_task")) {
            result.next();
            return result.getString(1);
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
