package com.databridge.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class CdcEventRepositoryIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11");

    static PGSimpleDataSource dataSource;

    @BeforeAll
    static void migrate() {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @Test
    void duplicatesAreIgnoredAndOtherEventsRemainOrdered() throws Exception {
        var repository = new CdcEventRepository(dataSource);
        var taskId = UUID.randomUUID();
        var first = event("a", "c", 1L);
        var second = event("b", "u", 1L);

        assertEquals(1, repository.insertBatch(taskId, List.of(first, first)));
        assertEquals(1, repository.insertBatch(taskId, List.of(second)));
        assertEquals(List.of("c", "u"), operations(taskId));
    }

    @Test
    void secondInvalidEventRollsBackTheWholeBatch() throws Exception {
        var repository = new CdcEventRepository(dataSource);
        var taskId = UUID.randomUUID();
        assertThrows(DataAccessException.class, () -> repository.insertBatch(
                taskId, List.of(event("c", "c", 2L), event("d", "x", 2L))));
        assertEquals(List.of(), operations(taskId));
    }

    private CdcEvent event(String key, String operation, long rowId) {
        return new CdcEvent(key.repeat(64), "{}", "{\"file\":\"mysql-bin.000001\"}",
                "databridge_source.orders", operation, rowId,
                "d".equals(operation) ? null : "{\"id\":" + rowId + "}");
    }

    private List<String> operations(UUID taskId) throws Exception {
        var values = new ArrayList<String>();
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("""
                     SELECT operation FROM databridge_meta.cdc_event
                     WHERE task_id = ? ORDER BY sequence
                     """)) {
            statement.setObject(1, taskId);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    values.add(result.getString(1));
                }
            }
        }
        return values;
    }
}
