package com.databridge.task;

import com.databridge.copy.CopyTable;
import java.util.Objects;
import java.util.UUID;
import java.sql.SQLException;
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

    public TaskRecord get(UUID taskId) {
        return jdbc.queryForObject("""
                SELECT source_schema, source_table, target_schema, target_table,
                       status, resume_status, error_message
                FROM databridge_meta.sync_task WHERE task_id = ?
                """, (row, ignored) -> new TaskRecord(taskId,
                new CopyTable(row.getString(1), row.getString(2),
                        row.getString(3), row.getString(4)),
                row.getString(5), row.getString(6), row.getString(7)), taskId);
    }

    public void start(UUID taskId) {
        requireUpdated(jdbc.update("""
                UPDATE databridge_meta.sync_task
                SET status = 'CAPTURING', error_message = NULL, updated_at = now()
                WHERE task_id = ? AND status IN
                    ('CREATED', 'CAPTURING', 'COPYING', 'REPLAYING', 'STREAMING',
                     'PAUSED', 'STOPPED', 'FAILED')
                """, taskId), taskId, "start");
    }

    public void parkAfterRecovery(UUID taskId, String resumeStage) {
        if (!resumeStage.equals("COPYING") && !resumeStage.equals("REPLAYING")) {
            throw new IllegalArgumentException("Invalid resumed stage: " + resumeStage);
        }
        requireUpdated(jdbc.update("""
                UPDATE databridge_meta.sync_task
                SET status = 'PAUSED', resume_status = ?, updated_at = now()
                WHERE task_id = ? AND status = 'CAPTURING'
                """, resumeStage, taskId), taskId, "park");
    }

    public boolean advance(UUID taskId, String from, String to) {
        if (!(from.equals("CAPTURING") && (to.equals("COPYING") || to.equals("REPLAYING"))
                || from.equals("COPYING") && to.equals("REPLAYING")
                || from.equals("REPLAYING") && to.equals("STREAMING"))) {
            throw new IllegalArgumentException("Invalid task stage transition: " + from + " -> " + to);
        }
        return jdbc.update("""
                UPDATE databridge_meta.sync_task
                SET status = ?, resume_status = NULL, updated_at = now()
                WHERE task_id = ? AND status = ?
                """, to, taskId, from) == 1;
    }

    public void pause(UUID taskId) {
        requireUpdated(jdbc.update("""
                UPDATE databridge_meta.sync_task
                SET resume_status = status, status = 'PAUSED', updated_at = now()
                WHERE task_id = ? AND status IN ('CAPTURING', 'COPYING', 'REPLAYING', 'STREAMING')
                """, taskId), taskId, "pause");
    }

    public void resume(UUID taskId) {
        requireUpdated(jdbc.update("""
                UPDATE databridge_meta.sync_task
                SET status = resume_status, resume_status = NULL, updated_at = now()
                WHERE task_id = ? AND status = 'PAUSED' AND resume_status IS NOT NULL
                """, taskId), taskId, "resume");
    }

    public void stop(UUID taskId) {
        requireUpdated(jdbc.update("""
                UPDATE databridge_meta.sync_task
                SET resume_status = COALESCE(resume_status, status),
                    status = 'STOPPED', updated_at = now()
                WHERE task_id = ? AND status IN
                    ('CAPTURING', 'COPYING', 'REPLAYING', 'STREAMING', 'PAUSED')
                """, taskId), taskId, "stop");
    }

    public void fail(UUID taskId, Throwable error) {
        String message = safeFailureReason(Objects.requireNonNull(error));
        jdbc.update("""
                UPDATE databridge_meta.sync_task
                SET resume_status = COALESCE(resume_status, status),
                    status = 'FAILED', error_message = ?, updated_at = now()
                WHERE task_id = ? AND status IN
                    ('CAPTURING', 'COPYING', 'REPLAYING', 'STREAMING', 'PAUSED')
                """, message, taskId);
    }

    private static String safeFailureReason(Throwable error) {
        var reason = new StringBuilder();
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 6; depth++, cause = cause.getCause()) {
            if (!reason.isEmpty()) {
                reason.append(" -> ");
            }
            reason.append(cause.getClass().getSimpleName());
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                reason.append(" [SQLState ").append(sql.getSQLState()).append(']');
            }
        }
        return reason.toString();
    }

    private static void requireUpdated(int count, UUID taskId, String operation) {
        if (count != 1) {
            throw new IllegalStateException("Could not " + operation + " task " + taskId + " from its current state");
        }
    }

    public record TaskRecord(UUID taskId, CopyTable table, String status,
                             String resumeStatus, String errorMessage) { }
}
