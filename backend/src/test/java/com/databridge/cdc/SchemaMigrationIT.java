package com.databridge.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class SchemaMigrationIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();
    }

    @Test
    void migrationCreatesCdcEventTable() throws Exception {
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT count(*) FROM information_schema.tables
                     WHERE table_schema = 'databridge_meta' AND table_name = 'cdc_event'
                     """)) {
            result.next();
            assertEquals(1, result.getInt(1));
        }
    }

    @Test
    void eventKeyIsUniquePerTaskAndSequenceIncreases() throws Exception {
        var taskId = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            long first = insert(connection, taskId, "a".repeat(64), 1L);
            assertThrows(SQLException.class, () -> insert(connection, taskId, "a".repeat(64), 1L));
            long second = insert(connection, taskId, "b".repeat(64), 2L);
            assertTrue(second > first);
        }
    }

    private long insert(java.sql.Connection connection, UUID taskId, String eventKey, long rowId)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO databridge_meta.cdc_event
                    (task_id, event_key, source_partition, source_offset,
                     table_name, operation, row_id, after_row)
                VALUES (?, ?, '{}'::jsonb, '{}'::jsonb,
                        'databridge_source.sync_probe', 'c', ?, '{}'::jsonb)
                RETURNING sequence
                """)) {
            statement.setObject(1, taskId);
            statement.setString(2, eventKey);
            statement.setLong(3, rowId);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }
}
