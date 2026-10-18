package com.databridge.cdc;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class CdcEventRepository {
    private static final String INSERT = """
            INSERT INTO databridge_meta.cdc_event
                (task_id, event_key, source_partition, source_offset,
                 table_name, operation, row_id, after_row)
            VALUES (?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?::jsonb)
            ON CONFLICT (task_id, event_key) DO NOTHING
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public CdcEventRepository(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    public int insertBatch(UUID taskId, List<CdcEvent> events) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(events, "events");
        if (events.isEmpty()) {
            return 0;
        }
        return transaction.execute(status -> {
            int inserted = 0;
            for (CdcEvent event : events) {
                inserted += jdbc.update(INSERT,
                        taskId, event.eventKey(), event.sourcePartitionJson(),
                        event.sourceOffsetJson(), event.tableName(), event.operation(),
                        event.rowId(), event.afterRowJson());
            }
            return inserted;
        });
    }
}
