package com.databridge.cdc;

import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.RecordChangeEvent;
import io.debezium.engine.format.ChangeEventFormat;
import io.debezium.embedded.Connect;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.connect.source.SourceRecord;

public final class CdcCapture implements AutoCloseable {
    private final UUID taskId;
    private final String tableName;
    private final Properties settings;
    private final CdcEventRepository repository;
    private final CdcRecordMapper mapper = new CdcRecordMapper();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final ExecutorService executor;
    private DebeziumEngine<RecordChangeEvent<SourceRecord>> engine;
    private volatile boolean closing;

    public CdcCapture(
            UUID taskId, String tableName, Properties connectionSettings,
            CdcEventRepository repository) {
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.tableName = Objects.requireNonNull(tableName, "tableName");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.settings = new Properties();
        this.settings.putAll(Objects.requireNonNull(connectionSettings, "connectionSettings"));
        this.executor = Executors.newSingleThreadExecutor(
                runnable -> new Thread(runnable, "databridge-cdc-" + taskId));
    }

    public synchronized void start() {
        if (engine != null) {
            throw new IllegalStateException("CDC capture has already started");
        }
        var properties = new Properties();
        properties.putAll(settings);
        properties.setProperty("name", "databridge-" + taskId);
        properties.setProperty("connector.class", "io.debezium.connector.mysql.MySqlConnector");
        properties.setProperty("snapshot.mode", "no_data");
        properties.setProperty("table.include.list", tableName.replace(".", "\\."));
        properties.setProperty("include.schema.changes", "false");
        properties.setProperty("tombstones.on.delete", "false");
        properties.setProperty("tasks.max", "1");
        properties.setProperty("record.processing.threads", "1");
        properties.setProperty("record.processing.order", "ORDERED");
        properties.setProperty("decimal.handling.mode", "string");
        properties.setProperty("time.precision.mode", "isostring");
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

        engine = DebeziumEngine.create(ChangeEventFormat.of(Connect.class))
                .using(properties)
                .notifying(this::handleBatch)
                .using((success, message, error) -> {
                    if (!closing && (!success || error != null)) {
                        failure.compareAndSet(null,
                                error != null ? error : new IllegalStateException(message));
                    }
                })
                .build();
        executor.execute(() -> {
            try {
                engine.run();
            } catch (Throwable error) {
                if (!closing) {
                    failure.compareAndSet(null, error);
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
            if (!tableName.equals(event.tableName())) {
                throw new IllegalArgumentException("Unexpected CDC table: " + event.tableName());
            }
            events.add(event);
        }
        repository.insertBatch(taskId, events);
        for (var change : records) {
            committer.markProcessed(change);
        }
        committer.markBatchFinished();
    }

    public Throwable failure() {
        return failure.get();
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
