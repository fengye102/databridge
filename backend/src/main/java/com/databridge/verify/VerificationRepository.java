package com.databridge.verify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Stores final verification conclusions and bounded, value-free discrepancy samples. */
public final class VerificationRepository {
    private static final int MAX_SAMPLES = 100;
    private final DataSource target;
    private final JdbcTemplate jdbc;
    private final JsonMapper json = new JsonMapper();

    public VerificationRepository(DataSource target) {
        this.target = Objects.requireNonNull(target);
        this.jdbc = new JdbcTemplate(target);
    }

    public VerificationReport save(VerificationReport report) {
        Objects.requireNonNull(report);
        if (report.differences().size() > MAX_SAMPLES) {
            throw new IllegalArgumentException("Too many difference samples");
        }
        try (var pg = target.getConnection()) {
            pg.setAutoCommit(false);
            try {
                try (var insert = pg.prepareStatement("""
                        INSERT INTO databridge_meta.verification_run
                        (run_id, task_id, status, source_rows, target_rows, difference_rows,
                         reason_code, started_at, completed_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    insert.setObject(1, report.runId());
                    insert.setObject(2, report.taskId());
                    insert.setString(3, report.status());
                    insert.setObject(4, report.sourceRows());
                    insert.setObject(5, report.targetRows());
                    insert.setObject(6, report.differenceRows());
                    insert.setString(7, report.reasonCode());
                    insert.setObject(8, java.time.OffsetDateTime.ofInstant(
                            report.startedAt(), java.time.ZoneOffset.UTC));
                    insert.setObject(9, java.time.OffsetDateTime.ofInstant(
                            report.completedAt(), java.time.ZoneOffset.UTC));
                    insert.executeUpdate();
                }
                try (var insert = pg.prepareStatement("""
                        INSERT INTO databridge_meta.verification_diff
                            (run_id, row_id, kind, columns) VALUES (?, ?, ?, ?::jsonb)
                        """)) {
                    for (var difference : report.differences()) {
                        insert.setObject(1, report.runId());
                        insert.setLong(2, difference.rowId());
                        insert.setString(3, difference.kind());
                        insert.setString(4, json.writeValueAsString(difference.columns()));
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                pg.commit();
                return report;
            } catch (SQLException | RuntimeException | JsonProcessingException e) {
                pg.rollback();
                throw e;
            }
        } catch (SQLException | JsonProcessingException e) {
            throw new IllegalStateException("Verification report write failed", e);
        }
    }

    public VerificationReport get(UUID taskId, UUID runId) {
        VerificationReport header = jdbc.queryForObject("""
                SELECT status, source_rows, target_rows, difference_rows, reason_code,
                       started_at, completed_at
                FROM databridge_meta.verification_run WHERE task_id = ? AND run_id = ?
                """, (row, ignored) -> new VerificationReport(runId, taskId,
                row.getString(1), row.getObject(2, Long.class), row.getObject(3, Long.class),
                row.getObject(4, Long.class), row.getString(5),
                row.getObject(6, java.time.OffsetDateTime.class).toInstant(),
                row.getObject(7, java.time.OffsetDateTime.class).toInstant(), List.of()), taskId, runId);
        List<VerificationDiff> differences = jdbc.query("""
                SELECT row_id, kind, columns::text
                FROM databridge_meta.verification_diff WHERE run_id = ? ORDER BY row_id
                """, (row, ignored) -> new VerificationDiff(row.getLong(1), row.getString(2),
                parseColumns(row.getString(3))), runId);
        return new VerificationReport(header.runId(), header.taskId(), header.status(),
                header.sourceRows(), header.targetRows(), header.differenceRows(),
                header.reasonCode(), header.startedAt(), header.completedAt(), differences);
    }

    private List<String> parseColumns(String raw) {
        try {
            return json.readValue(raw, new TypeReference<ArrayList<String>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid stored difference columns", e);
        }
    }

    public record VerificationDiff(long rowId, String kind, List<String> columns) {
        public VerificationDiff {
            columns = List.copyOf(columns);
        }
    }

    public record VerificationReport(UUID runId, UUID taskId, String status,
                                     Long sourceRows, Long targetRows, Long differenceRows,
                                     String reasonCode, Instant startedAt, Instant completedAt,
                                     List<VerificationDiff> differences) {
        public VerificationReport {
            differences = List.copyOf(differences);
        }
    }
}
