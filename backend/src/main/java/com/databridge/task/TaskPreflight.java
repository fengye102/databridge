package com.databridge.task;

import com.databridge.copy.CopyTable;
import com.databridge.copy.FullCopyWorker;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/** Rejects table mappings that the current copy and CDC paths cannot safely process. */
public final class TaskPreflight {
    private final DataSource source;
    private final DataSource target;

    public TaskPreflight(DataSource source, DataSource target) {
        this.source = Objects.requireNonNull(source);
        this.target = Objects.requireNonNull(target);
    }

    public void validate(CopyTable table) {
        Objects.requireNonNull(table);
        if ("sync_probe".equals(table.sourceTable())) {
            throw new IllegalArgumentException("sync_probe is reserved for CDC barriers");
        }
        try (var mysql = source.getConnection(); var pg = target.getConnection()) {
            requirePrimaryKey(mysql, table.sourceSchema(), table.sourceTable(), true);
            requirePrimaryKey(pg, table.targetSchema(), table.targetTable(), false);
            requireProbeTable(mysql, table.sourceSchema());
            requireEmptyTarget(pg, table);
            requireTemporalPrecision(mysql, pg, table);
        } catch (SQLException e) {
            throw new IllegalArgumentException("Task preflight could not inspect source or target", e);
        }
        new FullCopyWorker(source, target).validateTable(table);
    }

    private void requirePrimaryKey(Connection connection, String schema, String table, boolean mysql)
            throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        List<String> keys = new ArrayList<>();
        try (var result = metadata.getPrimaryKeys(mysql ? schema : null, mysql ? null : schema, table)) {
            while (result.next()) {
                keys.add(result.getString("COLUMN_NAME"));
            }
        }
        if (!keys.equals(List.of("id"))) {
            throw new IllegalArgumentException("Exactly one id primary key is required: " + schema + "." + table);
        }
        try (var result = metadata.getColumns(mysql ? schema : null, mysql ? null : schema, table, "id")) {
            if (!result.next() || result.getInt("DATA_TYPE") != Types.BIGINT) {
                throw new IllegalArgumentException("id must be signed BIGINT: " + schema + "." + table);
            }
        }
    }

    private void requireTemporalPrecision(Connection mysql, Connection pg, CopyTable table)
            throws SQLException {
        try (var sourceColumns = mysql.prepareStatement("""
                SELECT COLUMN_NAME, DATETIME_PRECISION
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
                    AND DATA_TYPE IN ('datetime', 'timestamp')
                """)) {
            sourceColumns.setString(1, table.sourceSchema());
            sourceColumns.setString(2, table.sourceTable());
            try (var columns = sourceColumns.executeQuery()) {
                while (columns.next()) {
                    try (var targetColumn = pg.prepareStatement("""
                            SELECT datetime_precision FROM information_schema.columns
                            WHERE table_schema = ? AND table_name = ? AND column_name = ?
                            """)) {
                        targetColumn.setString(1, table.targetSchema());
                        targetColumn.setString(2, table.targetTable());
                        targetColumn.setString(3, columns.getString(1));
                        try (var precision = targetColumn.executeQuery()) {
                            if (!precision.next() || precision.getObject(1) == null
                                    || precision.getInt(1) < columns.getInt(2)) {
                                throw new IllegalArgumentException("Target timestamp precision is too narrow: "
                                        + columns.getString(1));
                            }
                        }
                    }
                }
            }
        }
    }

    private void requireProbeTable(Connection mysql, String schema) throws SQLException {
        try (var statement = mysql.createStatement();
             var result = statement.executeQuery(
                     "SELECT `id`, `marker` FROM `" + schema + "`.`sync_probe` WHERE 1 = 0")) {
            var metadata = result.getMetaData();
            if (metadata.getColumnType(1) != Types.BIGINT
                    || metadata.getColumnType(2) != Types.CHAR
                    || metadata.getPrecision(2) != 36) {
                throw new IllegalArgumentException("sync_probe requires BIGINT id and CHAR(36) marker");
            }
        } catch (SQLException e) {
            throw new IllegalArgumentException("Source sync_probe table is missing or unreadable", e);
        }
        requirePrimaryKey(mysql, schema, "sync_probe", true);
        try (var column = mysql.getMetaData().getColumns(schema, null, "sync_probe", "id")) {
            if (!column.next() || !"YES".equalsIgnoreCase(column.getString("IS_AUTOINCREMENT"))) {
                throw new IllegalArgumentException("sync_probe.id must be auto-incrementing");
            }
        }
    }

    private void requireEmptyTarget(Connection pg, CopyTable table) throws SQLException {
        try (var statement = pg.createStatement();
             var result = statement.executeQuery(
                     "SELECT 1 FROM " + table.targetQualifiedName() + " LIMIT 1")) {
            if (result.next()) {
                throw new IllegalArgumentException("Target table must be empty before task creation");
            }
        }
    }
}
