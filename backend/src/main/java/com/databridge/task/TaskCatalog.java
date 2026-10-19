package com.databridge.task;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/** Read-only metadata for preconfigured source and target connections. */
public final class TaskCatalog {
    private final DataSource source;
    private final DataSource target;
    private final String sourceDatabase;

    public TaskCatalog(DataSource source, DataSource target, String sourceDatabase) {
        this.source = Objects.requireNonNull(source);
        this.target = Objects.requireNonNull(target);
        this.sourceDatabase = Objects.requireNonNull(sourceDatabase);
    }

    public Catalog read() {
        return new Catalog(endpoint(source, true), endpoint(target, false));
    }

    private Endpoint endpoint(DataSource database, boolean mysql) {
        try (var connection = database.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            String catalog = mysql ? sourceDatabase : null;
            var tables = new ArrayList<Table>();
            try (var rows = metadata.getTables(catalog, null, "%", new String[] {"TABLE"})) {
                while (rows.next()) {
                    String schema = mysql ? sourceDatabase : rows.getString("TABLE_SCHEM");
                    String name = rows.getString("TABLE_NAME");
                    if ((mysql && name.equals("sync_probe"))
                            || (!mysql && (systemSchema(schema) || name.equals("flyway_schema_history")))) {
                        continue;
                    }
                    tables.add(new Table(schema, name,
                            columns(metadata, mysql ? sourceDatabase : null,
                                    mysql ? null : schema, name)));
                }
            }
            tables.sort(Comparator.comparing(Table::schema).thenComparing(Table::table));
            return new Endpoint(true, List.copyOf(tables));
        } catch (SQLException | RuntimeException e) {
            return new Endpoint(false, List.of());
        }
    }

    private static List<Column> columns(DatabaseMetaData metadata,
                                        String catalog, String schema, String table) throws SQLException {
        var columns = new ArrayList<Column>();
        try (var rows = metadata.getColumns(catalog, schema, table, "%")) {
            while (rows.next()) {
                columns.add(new Column(rows.getString("COLUMN_NAME"), rows.getString("TYPE_NAME")));
            }
        }
        return List.copyOf(columns);
    }

    private static boolean systemSchema(String schema) {
        return schema == null || schema.equals("databridge_meta")
                || schema.equals("information_schema") || schema.equals("pg_catalog")
                || schema.startsWith("pg_toast") || schema.startsWith("pg_temp");
    }

    public record Catalog(Endpoint source, Endpoint target) { }
    public record Endpoint(boolean reachable, List<Table> tables) { }
    public record Table(String schema, String table, List<Column> columns) { }
    public record Column(String name, String type) { }
}
