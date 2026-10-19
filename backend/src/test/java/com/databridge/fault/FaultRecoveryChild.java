package com.databridge.fault;

import com.databridge.task.TaskCoordinator;
import java.util.Properties;
import java.util.UUID;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Deliberately long-lived process that the parent benchmark must terminate forcibly. */
public final class FaultRecoveryChild {
    private FaultRecoveryChild() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("task ID and batch size required");
        var source = new DriverManagerDataSource(required("FAULT_SOURCE_URL"),
                required("FAULT_SOURCE_USER"), required("FAULT_SOURCE_PASSWORD"));
        var target = new PGSimpleDataSource();
        target.setURL(required("FAULT_TARGET_URL"));
        target.setUser(required("FAULT_TARGET_USER"));
        target.setPassword(required("FAULT_TARGET_PASSWORD"));
        var settings = new Properties();
        settings.setProperty("database.hostname", required("FAULT_MYSQL_HOST"));
        settings.setProperty("database.port", required("FAULT_MYSQL_PORT"));
        settings.setProperty("database.user", required("FAULT_SOURCE_USER"));
        settings.setProperty("database.password", required("FAULT_SOURCE_PASSWORD"));
        settings.setProperty("database.server.id", "5542");
        settings.setProperty("database.include.list", "databridge_source");
        settings.setProperty("offset.storage.jdbc.connection.url", required("FAULT_TARGET_URL"));
        settings.setProperty("offset.storage.jdbc.connection.user", required("FAULT_TARGET_USER"));
        settings.setProperty("offset.storage.jdbc.connection.password", required("FAULT_TARGET_PASSWORD"));
        settings.setProperty("schema.history.internal.jdbc.connection.url", required("FAULT_TARGET_URL"));
        settings.setProperty("schema.history.internal.jdbc.connection.user", required("FAULT_TARGET_USER"));
        settings.setProperty("schema.history.internal.jdbc.connection.password", required("FAULT_TARGET_PASSWORD"));
        try (var coordinator = new TaskCoordinator(source, target, settings, Integer.parseInt(args[1]))) {
            coordinator.start(UUID.fromString(args[0]));
            System.out.println("FAULT_CHILD_STARTED " + args[0]);
            while (true) Thread.sleep(1000);
        }
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }
}
