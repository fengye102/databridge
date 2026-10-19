package com.databridge.cdc;

import io.debezium.config.CommonConnectorConfig;
import io.debezium.pipeline.notification.Notification;
import io.debezium.pipeline.notification.channels.NotificationChannel;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Routes Debezium initial-snapshot notifications to the matching embedded capture. */
public final class CaptureReadinessChannel implements NotificationChannel {
    private static final ConcurrentHashMap<String, CompletableFuture<Void>> WAITERS = new ConcurrentHashMap<>();

    static CompletableFuture<Void> register(String connectorName) {
        var future = new CompletableFuture<Void>();
        if (WAITERS.putIfAbsent(connectorName, future) != null) {
            throw new IllegalStateException("CDC capture is already registered: " + connectorName);
        }
        return future;
    }

    static void unregister(String connectorName, CompletableFuture<Void> future) {
        WAITERS.remove(connectorName, future);
    }

    @Override
    public String name() {
        return "databridge-ready";
    }

    @Override
    public void init(CommonConnectorConfig config) {
    }

    @Override
    public void send(Notification notification) {
        if (!"Initial Snapshot".equals(notification.getAggregateType())) {
            return;
        }
        String connectorName = notification.getAdditionalData().get("connector_name");
        var future = WAITERS.get(connectorName);
        if (future == null) {
            return;
        }
        switch (notification.getType()) {
            case "COMPLETED", "SKIPPED" -> future.complete(null);
            case "ABORTED" -> future.completeExceptionally(
                    new IllegalStateException("Debezium initial snapshot aborted: " + connectorName));
            default -> { }
        }
    }

    @Override
    public void close() {
    }
}
