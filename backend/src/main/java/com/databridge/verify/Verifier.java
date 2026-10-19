package com.databridge.verify;

import com.databridge.copy.CopyTable;
import com.databridge.copy.FullCopyWorker;
import com.databridge.task.TaskRepository;
import com.databridge.verify.VerificationRepository.VerificationDiff;
import com.databridge.verify.VerificationRepository.VerificationReport;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;

/** Bounded keyset comparison, valid only when two applied probes enclose a quiet source window. */
public final class Verifier {
    private static final Set<UUID> RUNNING = ConcurrentHashMap.newKeySet();
    private static final int MAX_SAMPLES = 100;
    private final DataSource source;
    private final DataSource target;
    private final TaskRepository tasks;
    private final VerificationBarrier barrier;
    private final VerificationRepository reports;

    public Verifier(DataSource source, DataSource target) {
        this.source = Objects.requireNonNull(source);
        this.target = Objects.requireNonNull(target);
        this.tasks = new TaskRepository(source, target);
        this.barrier = new VerificationBarrier(source, target);
        this.reports = new VerificationRepository(target);
    }

    public VerificationReport verify(UUID taskId, Duration timeout, int batchSize) {
        Objects.requireNonNull(taskId);
        Objects.requireNonNull(timeout);
        if (timeout.isNegative() || timeout.isZero() || batchSize <= 0) {
            throw new IllegalArgumentException("Verification timeout and batch size must be positive");
        }
        var task = tasks.get(taskId);
        if (!"STREAMING".equals(task.status())) {
            throw new IllegalStateException("Only STREAMING tasks may be verified");
        }
        if (!RUNNING.add(taskId)) {
            throw new IllegalStateException("A verification is already running for this task");
        }
        UUID runId = UUID.randomUUID();
        Instant started = now();
        try {
            CopyTable table = task.table();
            VerificationReport result;
            try {
                new FullCopyWorker(source, target).validateTable(table);
                long first = barrier.writeAndAwait(taskId, table, timeout);
                Scan scan = compare(table, batchSize);
                long last = barrier.writeAndAwait(taskId, table, timeout);
                result = barrier.businessEventBetween(taskId, table, first, last)
                        ? inconclusive(runId, taskId, "SOURCE_CHANGED", started)
                        : new VerificationReport(runId, taskId,
                                scan.differenceRows() == 0 ? "CONSISTENT" : "DIFFERENT",
                                scan.sourceRows(), scan.targetRows(), scan.differenceRows(),
                                null, started, now(), scan.samples());
            } catch (VerificationBarrier.BarrierUnavailableException e) {
                result = inconclusive(runId, taskId, e.reasonCode(), started);
            } catch (RuntimeException e) {
                result = inconclusive(runId, taskId, "SCAN_OR_BARRIER_FAILED", started);
            }
            return reports.save(result);
        } finally {
            RUNNING.remove(taskId);
        }
    }

    public VerificationReport report(UUID taskId, UUID runId) {
        return reports.get(taskId, runId);
    }

    public List<VerificationReport> reports(UUID taskId) {
        return reports.listRecent(taskId);
    }

    private static VerificationReport inconclusive(
            UUID runId, UUID taskId, String reason, Instant started) {
        return new VerificationReport(runId, taskId, "INCONCLUSIVE",
                null, null, null, reason, started, now(), List.of());
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private Scan compare(CopyTable table, int batchSize) {
        var sourceRows = new PagedRows(source, table.sourceQualifiedName(), true, batchSize);
        var targetRows = new PagedRows(target, table.targetQualifiedName(), false, batchSize);
        long sourceCount = 0;
        long targetCount = 0;
        long differenceCount = 0;
        var samples = new ArrayList<VerificationDiff>();
        while (true) {
            Row left = sourceRows.peek();
            Row right = targetRows.peek();
            if (left == null && right == null) {
                return new Scan(sourceCount, targetCount, differenceCount, samples);
            }
            if (right == null || left != null && left.id() < right.id()) {
                sourceCount++;
                differenceCount++;
                sample(samples, new VerificationDiff(left.id(), "MISSING_TARGET", List.of()));
                sourceRows.advance();
            } else if (left == null || right.id() < left.id()) {
                targetCount++;
                differenceCount++;
                sample(samples, new VerificationDiff(right.id(), "EXTRA_TARGET", List.of()));
                targetRows.advance();
            } else {
                sourceCount++;
                targetCount++;
                if (!left.fingerprint().equals(right.fingerprint())) {
                    differenceCount++;
                    var names = new TreeSet<String>();
                    names.addAll(left.values().keySet());
                    names.addAll(right.values().keySet());
                    names.removeIf(name -> Objects.equals(left.values().get(name), right.values().get(name)));
                    sample(samples, new VerificationDiff(left.id(), "VALUE_MISMATCH", List.copyOf(names)));
                }
                sourceRows.advance();
                targetRows.advance();
            }
        }
    }

    private static void sample(List<VerificationDiff> samples, VerificationDiff difference) {
        if (samples.size() < MAX_SAMPLES) {
            samples.add(difference);
        }
    }

    private record Scan(long sourceRows, long targetRows, long differenceRows,
                        List<VerificationDiff> samples) { }
    private record Row(long id, Map<String, String> values, String fingerprint) { }

    private static final class PagedRows {
        private final DataSource database;
        private final String table;
        private final boolean mysql;
        private final int batchSize;
        private Long lastId;
        private List<Row> batch = List.of();
        private int index;
        private boolean exhausted;

        PagedRows(DataSource database, String table, boolean mysql, int batchSize) {
            this.database = database;
            this.table = table;
            this.mysql = mysql;
            this.batchSize = batchSize;
        }

        Row peek() {
            if (index >= batch.size() && !exhausted) {
                load();
            }
            return index < batch.size() ? batch.get(index) : null;
        }

        void advance() {
            index++;
        }

        private void load() {
            String id = mysql ? "`id`" : "\"id\"";
            String sql = "SELECT * FROM " + table + (lastId == null ? "" : " WHERE " + id + " > ?")
                    + " ORDER BY " + id + " LIMIT ?";
            var next = new ArrayList<Row>(batchSize);
            try (var connection = database.getConnection()) {
                if (mysql) {
                    try (var zone = connection.createStatement()) {
                        zone.execute("SET time_zone = '+00:00'");
                    }
                }
                try (var query = connection.prepareStatement(sql)) {
                    int parameter = 1;
                    if (lastId != null) {
                        query.setLong(parameter++, lastId);
                    }
                    query.setInt(parameter, batchSize);
                    try (var rows = query.executeQuery()) {
                        ResultSetMetaData metadata = rows.getMetaData();
                        while (rows.next()) {
                            var values = new TreeMap<String, String>();
                            for (int i = 1; i <= metadata.getColumnCount(); i++) {
                                String type = metadata.getColumnTypeName(i);
                                Object value = metadata.getColumnType(i) == Types.TIMESTAMP
                                        ? rows.getObject(i, LocalDateTime.class)
                                        : (type.equalsIgnoreCase("json") || type.equalsIgnoreCase("jsonb"))
                                                ? rows.getString(i) : rows.getObject(i);
                                values.put(metadata.getColumnName(i), RowCanonicalizer.normalize(type, value));
                            }
                            long rowId = rows.getLong("id");
                            if (rows.wasNull()) {
                                throw new IllegalStateException("Null id during verification");
                            }
                            next.add(new Row(rowId, values, RowCanonicalizer.fingerprint(values)));
                            lastId = rowId;
                        }
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Verification scan failed", e);
            }
            batch = next;
            index = 0;
            exhausted = next.isEmpty();
        }
    }
}
