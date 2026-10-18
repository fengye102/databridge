package com.databridge.task;

import com.databridge.copy.CopyTable;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

public final class TaskRepository {
    private final TaskPreflight preflight;
    private final JdbcTemplate jdbc;

    public TaskRepository(DataSource source, DataSource target) {
        preflight = new TaskPreflight(source, target);
        jdbc = new JdbcTemplate(Objects.requireNonNull(target));
    }

    public UUID create(CopyTable table) {
        preflight.validate(table);
        UUID taskId = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO databridge_meta.sync_task
                        (task_id, source_schema, source_table, target_schema, target_table)
                    VALUES (?, ?, ?, ?, ?)
                    """, taskId, table.sourceSchema(), table.sourceTable(),
                    table.targetSchema(), table.targetTable());
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("A task already owns target " + table.targetQualifiedName(), e);
        }
        return taskId;
    }
}
