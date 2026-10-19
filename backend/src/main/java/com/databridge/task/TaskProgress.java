package com.databridge.task;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** A display snapshot from durable copy and CDC application state, not an estimated percent. */
public final class TaskProgress {
    private final JdbcTemplate jdbc;
    private final TaskRepository tasks;

    public TaskProgress(DataSource source, DataSource target) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(target));
        this.tasks = new TaskRepository(source, target);
    }

    public Snapshot get(UUID taskId) {
        tasks.get(taskId);
        List<CopyState> copy = jdbc.query("""
                SELECT high_watermark, last_id, completed
                FROM databridge_meta.full_checkpoint WHERE task_id = ?
                """, (row, ignored) -> new CopyState(
                row.getObject(1, Long.class), row.getObject(2, Long.class),
                row.getBoolean(3)), taskId);
        long[] events = jdbc.queryForObject("""
                SELECT COUNT(*), COUNT(*) FILTER (WHERE sequence <= COALESCE(
                    (SELECT last_sequence FROM databridge_meta.event_cursor WHERE task_id = ?), 0))
                FROM databridge_meta.cdc_event WHERE task_id = ?
                """, (row, ignored) -> new long[] {row.getLong(1), row.getLong(2)}, taskId, taskId);
        CopyState state = copy.isEmpty() ? null : copy.getFirst();
        return new Snapshot(taskId, state == null ? null : state.highWatermark(),
                state == null ? null : state.lastCopiedId(),
                state == null ? null : state.copyComplete(),
                events[0], events[1], events[0] - events[1]);
    }

    private record CopyState(Long highWatermark, Long lastCopiedId, boolean copyComplete) { }

    public record Snapshot(UUID taskId, Long highWatermark, Long lastCopiedId,
                           Boolean copyComplete, long capturedEvents,
                           long appliedEvents, long pendingEvents) { }
}
