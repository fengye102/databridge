package com.databridge.cdc;

import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.RecordChangeEvent;
import io.debezium.engine.format.ChangeEventFormat;
import io.debezium.embedded.Connect;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.apache.kafka.connect.source.SourceRecord;

public final class CdcCapture implements AutoCloseable {
    private final UUID taskId;
    private final String tableName;
    private final Properties settings;
    private final CdcEventRepository repository;
    private final CdcRecordMapper mapper = new CdcRecordMapper();
    private final JsonMapper json = new JsonMapper();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final ExecutorService executor;
    private final String connectorName;
    private final String topicPrefix;
    private final String probeTableName;
    private final DataSource sourceProbe;
    private final AtomicBoolean probeSent = new AtomicBoolean();
    private final CompletableFuture<Void> probeCaptured = new CompletableFuture<>();
    private volatile String probeMarker;
    private CompletableFuture<Void> snapshotReady;
    private DebeziumEngine<RecordChangeEvent<SourceRecord>> engine;
    private volatile boolean closing;

    public CdcCapture(
            UUID taskId, String tableName, Properties connectionSettings,
            CdcEventRepository repository) {
        this(taskId, tableName, connectionSettings, repository, null);
    }

    public CdcCapture(
            UUID taskId, String tableName, Properties connectionSettings,
            CdcEventRepository repository, DataSource sourceProbe) {
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.tableName = Objects.requireNonNull(tableName, "tableName");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.settings = new Properties();
        this.settings.putAll(Objects.requireNonNull(connectionSettings, "connectionSettings"));
        this.connectorName = "databridge-" + taskId;
        this.topicPrefix = Objects.requireNonNull(settings.getProperty("topic.prefix"), "topic.prefix");
        if (!topicPrefix.matches("[A-Za-z_][A-Za-z0-9_-]*")) {
            throw new IllegalArgumentException("Invalid CDC topic.prefix: " + topicPrefix);
        }
        this.sourceProbe = sourceProbe;
        String[] sourceName = tableName.split("\\.", -1);
        if (sourceName.length != 2 || !sourceName[0].matches("[A-Za-z_][A-Za-z0-9_]*")
                || !sourceName[1].matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid CDC source table: " + tableName);
        }
        this.probeTableName = sourceName[0] + ".sync_probe";
        this.executor = Executors.newSingleThreadExecutor(
                runnable -> new Thread(runnable, "databridge-cdc-" + taskId));
    }

    public synchronized void start() {
        if (engine != null) {
            throw new IllegalStateException("CDC capture has already started");
        }
        var properties = new Properties();
        properties.putAll(settings);
        properties.setProperty("name", connectorName);
        properties.setProperty("connector.class", "io.debezium.connector.mysql.MySqlConnector");
        properties.setProperty("snapshot.mode", "no_data");
        properties.setProperty("table.include.list", tableName.replace(".", "\\.")
                + (sourceProbe == null ? "" : "," + probeTableName.replace(".", "\\.")));
        properties.setProperty("include.schema.changes", "false");
        properties.setProperty("tombstones.on.delete", "false");
        properties.setProperty("tasks.max", "1");
        properties.setProperty("record.processing.threads", "1");
        properties.setProperty("record.processing.order", "ORDERED");
        properties.setProperty("decimal.handling.mode", "string");
        properties.setProperty("time.precision.mode", "isostring");
        properties.setProperty("notification.enabled.channels", "databridge-ready");
        properties.setProperty("offset.storage", "io.debezium.storage.jdbc.offset.JdbcOffsetBackingStore");
        properties.setProperty("offset.storage.jdbc.connection.url",
                inMetadataSchema(settings.getProperty("offset.storage.jdbc.connection.url")));
        properties.setProperty("offset.storage.jdbc.table.name", "debezium_offset_storage");
        properties.setProperty("schema.history.internal", "io.debezium.storage.jdbc.history.JdbcSchemaHistory");
        properties.setProperty("schema.history.internal.jdbc.connection.url",
                inMetadataSchema(settings.getProperty("schema.history.internal.jdbc.connection.url")));
        properties.setProperty("schema.history.internal.jdbc.table.name", "debezium_database_history");
        properties.setProperty("offset.flush.interval.ms", "1000");
        properties.setProperty("errors.max.retries", "0");

        snapshotReady = CaptureReadinessChannel.register(topicPrefix);
        engine = DebeziumEngine.create(ChangeEventFormat.of(Connect.class))
                .using(properties)
                .notifying(this::handleBatch)
                .using((success, message, error) -> {
                    if (!closing && (!success || error != null)) {
                        Throwable cause = error != null ? error : new IllegalStateException(message);
                        failure.compareAndSet(null, cause);
                        snapshotReady.completeExceptionally(cause);
                    }
                })
                .build();
        executor.execute(() -> {
            try {
                engine.run();
            } catch (Throwable error) {
                if (!closing) {
                    failure.compareAndSet(null, error);
                    snapshotReady.completeExceptionally(error);
                }
            }
        });
    }

    private void handleBatch(
            List<RecordChangeEvent<SourceRecord>> records,
            DebeziumEngine.RecordCommitter<RecordChangeEvent<SourceRecord>> committer)
            throws InterruptedException {
        var events = new ArrayList<CdcEvent>(records.size());
        for (var change : records) {
            var event = mapper.map(change.record());
            if (!tableName.equals(event.tableName())
                    && !(sourceProbe != null && probeTableName.equals(event.tableName()))) {
                throw new IllegalArgumentException("Unexpected CDC table: " + event.tableName());
            }
            events.add(event);
        }
        repository.insertBatch(taskId, events);
        String expectedMarker = probeMarker;
        if (expectedMarker != null) {
            for (var event : events) {
                if (isProbeMarker(event, expectedMarker)) {
                    probeCaptured.complete(null);
                }
            }
        }
        for (var change : records) {
            committer.markProcessed(change);
        }
        committer.markBatchFinished();
    }

    private boolean isProbeMarker(CdcEvent event, String expectedMarker) {
        if (!probeTableName.equals(event.tableName()) || !"c".equals(event.operation())
                || event.afterRowJson() == null) {
            return false;
        }
        try {
            return expectedMarker.equals(json.readTree(event.afterRowJson()).path("marker").asText());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid CDC probe row", e);
        }
    }

    public Throwable failure() {
        return failure.get();
    }

    public void awaitReady(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero() || engine == null) {
            throw new IllegalArgumentException("CDC must be started and timeout must be positive");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (closing) {
                throw new IllegalStateException("CDC capture closed before readiness");
            }
            Throwable error = failure.get();
            if (error != null) {
                throw new IllegalStateException("CDC failed before readiness", error);
            }
            if (snapshotReady.isCompletedExceptionally()) {
                throw new IllegalStateException("Debezium initial snapshot failed");
            }
            if (snapshotReady.isDone()) {
                if (probeSent.compareAndSet(false, true)) {
                    writeProbe();
                }
                if (probeCaptured.isDone() && offsetDurable()) {
                    return;
                }
            }
            try {
                TimeUnit.MILLISECONDS.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for CDC readiness", e);
            }
        }
        throw new IllegalStateException("CDC readiness timed out after " + timeout
                + "; snapshotSignal=" + snapshotReady.isDone()
                + "; freshProbeCaptured=" + probeCaptured.isDone()
                + "; taskOffsetPresent=" + offsetDurable());
    }

    private void writeProbe() {
        if (sourceProbe == null) {
            throw new IllegalStateException("A source probe DataSource is required for CDC readiness");
        }
        probeMarker = UUID.randomUUID().toString();
        try (var connection = sourceProbe.getConnection();
             var statement = connection.prepareStatement(
                     "INSERT INTO `" + probeTableName.replace(".", "`.`")
                             + "` (`marker`) VALUES (?)")) {
            statement.setString(1, probeMarker);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not write source CDC readiness probe", e);
        }
    }

    private boolean offsetDurable() {
        try (var connection = DriverManager.getConnection(
                settings.getProperty("offset.storage.jdbc.connection.url"),
                settings.getProperty("offset.storage.jdbc.connection.user"),
                settings.getProperty("offset.storage.jdbc.connection.password"));
             var statement = connection.prepareStatement(
                     "SELECT 1 FROM databridge_meta.debezium_offset_storage "
                             + "WHERE offset_key::jsonb ->> 0 = ? "
                             + "AND offset_key::jsonb -> 1 ->> 'server' = ? LIMIT 1")) {
            statement.setString(1, connectorName);
            statement.setString(2, topicPrefix);
            try (var result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException e) {
            if ("42P01".equals(e.getSQLState())) {
                return false;
            }
            throw new IllegalStateException("Could not verify persistent CDC offset", e);
        }
    }

    private static String inMetadataSchema(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("PostgreSQL JDBC URL is required for Debezium state");
        }
        return jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=databridge_meta";
    }

    @Override
    public synchronized void close() throws IOException {
        closing = true;
        if (snapshotReady != null) {
            snapshotReady.completeExceptionally(new IllegalStateException("CDC capture closed"));
            CaptureReadinessChannel.unregister(topicPrefix, snapshotReady);
        }
        if (engine != null) {
            try {
                engine.close();
            } catch (IllegalStateException error) {
                if (failure.get() == null) {
                    throw error;
                }
            }
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                throw new IOException("CDC Engine did not stop within 30 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
            throw new IOException("Interrupted while stopping CDC Engine", e);
        }
    }
}
