package com.databridge.verify;

import com.databridge.copy.CopyTable;
import com.databridge.task.TaskRepository;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** A new source-side probe must pass through capture and application before each scan boundary. */
final class VerificationBarrier {
    private final JdbcTemplate source;
    private final JdbcTemplate target;
    private final TaskRepository tasks;

    VerificationBarrier(DataSource source, DataSource target) {
        this.source = new JdbcTemplate(Objects.requireNonNull(source));
        this.target = new JdbcTemplate(Objects.requireNonNull(target));
        this.tasks = new TaskRepository(source, target);
    }

    long writeAndAwait(UUID taskId, CopyTable table, Duration timeout) {
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Barrier timeout must be positive");
        }
        requireStreaming(taskId);
        String marker = UUID.randomUUID().toString();
        source.update("INSERT INTO `" + table.sourceSchema() + "`.`sync_probe` (`marker`) VALUES (?)", marker);
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            requireStreaming(taskId);
            var sequences = target.queryForList("""
                    SELECT sequence FROM databridge_meta.cdc_event
                    WHERE task_id = ? AND table_name = ? AND operation = 'c'
                      AND after_row ->> 'marker' = ?
                    ORDER BY sequence DESC LIMIT 1
                    """, Long.class, taskId, table.sourceSchema() + ".sync_probe", marker);
            if (!sequences.isEmpty()) {
                long sequence = sequences.getFirst();
                var cursors = target.queryForList("""
                        SELECT last_sequence FROM databridge_meta.event_cursor WHERE task_id = ?
                        """, Long.class, taskId);
                if (!cursors.isEmpty() && cursors.getFirst() >= sequence) {
                    return sequence;
                }
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BarrierUnavailableException("INTERRUPTED", e);
            }
        }
        throw new BarrierUnavailableException("BARRIER_TIMEOUT", null);
    }

    boolean businessEventBetween(UUID taskId, CopyTable table, long first, long last) {
        return Boolean.TRUE.equals(target.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM databridge_meta.cdc_event
                    WHERE task_id = ? AND table_name = ?
                      AND sequence > ? AND sequence < ?
                )
                """, Boolean.class, taskId, table.sourceSchema() + "." + table.sourceTable(), first, last));
    }

    private void requireStreaming(UUID taskId) {
        if (!"STREAMING".equals(tasks.get(taskId).status())) {
            throw new BarrierUnavailableException("TASK_NOT_STREAMING", null);
        }
    }

    static final class BarrierUnavailableException extends RuntimeException {
        private final String reasonCode;

        BarrierUnavailableException(String reasonCode, Throwable cause) {
            super(reasonCode, cause);
            this.reasonCode = reasonCode;
        }

        String reasonCode() {
            return reasonCode;
        }
    }
}
