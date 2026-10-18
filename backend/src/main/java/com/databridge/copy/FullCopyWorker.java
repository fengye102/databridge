package com.databridge.copy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

/** Copies one bounded source table; callers must start CDC before the first batch. */
public final class FullCopyWorker {
    private final DataSource source;
    private final DataSource target;

    public FullCopyWorker(DataSource source, DataSource target) {
        this.source = java.util.Objects.requireNonNull(source);
        this.target = java.util.Objects.requireNonNull(target);
    }

    public int copyNextBatch(UUID taskId, CopyTable table, int batchSize) {
        java.util.Objects.requireNonNull(taskId);
        java.util.Objects.requireNonNull(table);
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        try {
            initializeCheckpoint(taskId, table);
            return copyInTransaction(taskId, table, batchSize);
        } catch (SQLException e) {
            throw new IllegalStateException("Full-copy batch failed for task " + taskId, e);
        }
    }

    private void initializeCheckpoint(UUID taskId, CopyTable table) throws SQLException {
        try (var pg = target.getConnection()) {
            try (var exists = pg.prepareStatement(
                    "SELECT 1 FROM databridge_meta.full_checkpoint WHERE task_id = ?")) {
                exists.setObject(1, taskId);
                try (var result = exists.executeQuery()) {
                    if (result.next()) {
                        return;
                    }
                }
            }
        }
        Long highWatermark;
        try (var mysql = source.getConnection();
             var statement = mysql.createStatement();
             var result = statement.executeQuery("SELECT MAX(`id`) FROM " + table.sourceQualifiedName())) {
            result.next();
            highWatermark = result.getObject(1, Long.class);
        }
        try (var pg = target.getConnection();
             var insert = pg.prepareStatement("""
                     INSERT INTO databridge_meta.full_checkpoint
                         (task_id, high_watermark, last_id, completed)
                     VALUES (?, ?, NULL, ?)
                     ON CONFLICT (task_id) DO NOTHING
                     """)) {
            insert.setObject(1, taskId);
            insert.setObject(2, highWatermark);
            insert.setBoolean(3, highWatermark == null);
            insert.executeUpdate();
        }
    }

    private int copyInTransaction(UUID taskId, CopyTable table, int batchSize) throws SQLException {
        try (var pg = target.getConnection()) {
            pg.setAutoCommit(false);
            try {
                Checkpoint checkpoint = lockCheckpoint(pg, taskId);
                if (checkpoint.completed()) {
                    pg.commit();
                    return 0;
                }
                try (var mysql = source.getConnection()) {
                    try (var zone = mysql.createStatement()) {
                        zone.execute("SET time_zone = '+00:00'");
                    }
                    List<Column> columns = inspectColumns(mysql, pg, table);
                    String select = "SELECT * FROM " + table.sourceQualifiedName()
                            + (checkpoint.lastId() == null
                            ? " WHERE `id` <= ? ORDER BY `id` LIMIT ?"
                            : " WHERE `id` > ? AND `id` <= ? ORDER BY `id` LIMIT ?");
                    try (var read = mysql.prepareStatement(select)) {
                        int parameter = 1;
                        if (checkpoint.lastId() != null) {
                            read.setLong(parameter++, checkpoint.lastId());
                        }
                        read.setLong(parameter++, checkpoint.highWatermark());
                        read.setInt(parameter, batchSize);
                        try (var rows = read.executeQuery()) {
                            int copied = writeRows(pg, table, columns, rows, taskId);
                            pg.commit();
                            return copied;
                        }
                    }
                }
            } catch (SQLException | RuntimeException e) {
                pg.rollback();
                throw e;
            }
        }
    }

    private Checkpoint lockCheckpoint(Connection pg, UUID taskId) throws SQLException {
        try (var statement = pg.prepareStatement("""
                SELECT high_watermark, last_id, completed
                FROM databridge_meta.full_checkpoint WHERE task_id = ? FOR UPDATE
                """)) {
            statement.setObject(1, taskId);
            try (var row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new IllegalStateException("Checkpoint disappeared for task " + taskId);
                }
                return new Checkpoint(row.getObject(1, Long.class),
                        row.getObject(2, Long.class), row.getBoolean(3));
            }
        }
    }

    private List<Column> inspectColumns(Connection mysql, Connection pg, CopyTable table) throws SQLException {
        Map<String, TargetColumn> targetTypes = new HashMap<>();
        try (var statement = pg.createStatement();
             var rows = statement.executeQuery("SELECT * FROM " + table.targetQualifiedName() + " WHERE 1 = 0")) {
            var metadata = rows.getMetaData();
            for (int i = 1; i <= metadata.getColumnCount(); i++) {
                targetTypes.put(metadata.getColumnName(i).toLowerCase(Locale.ROOT),
                        new TargetColumn(metadata.getColumnTypeName(i).toLowerCase(Locale.ROOT),
                                metadata.getPrecision(i), metadata.getScale(i)));
            }
        }
        var columns = new ArrayList<Column>();
        try (var statement = mysql.createStatement();
             var rows = statement.executeQuery("SELECT * FROM " + table.sourceQualifiedName() + " WHERE 1 = 0")) {
            ResultSetMetaData metadata = rows.getMetaData();
            for (int i = 1; i <= metadata.getColumnCount(); i++) {
                String name = metadata.getColumnName(i);
                if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    throw new IllegalArgumentException("Unsupported column identifier: " + name);
                }
                TargetColumn targetColumn = targetTypes.remove(name.toLowerCase(Locale.ROOT));
                if (targetColumn == null) {
                    throw new IllegalArgumentException("Target column missing: " + name);
                }
                String sourceType = metadata.getColumnTypeName(i).toLowerCase(Locale.ROOT);
                if (sourceType.contains("unsigned") || (name.equals("id")
                        && metadata.getColumnType(i) != Types.BIGINT)) {
                    throw new IllegalArgumentException("Only signed BIGINT id and signed numeric columns are supported: "
                            + name);
                }
                boolean json = sourceType.equals("json");
                if (!compatible(sourceType, metadata.getColumnType(i), metadata.getPrecision(i),
                        metadata.getScale(i), targetColumn, json)) {
                    throw new IllegalArgumentException("Incompatible column " + name + ": "
                            + sourceType + " -> " + targetColumn.type());
                }
                columns.add(new Column(name, json,
                        sourceType.equals("datetime") || sourceType.equals("timestamp")));
            }
        }
        if (!targetTypes.isEmpty()) {
            throw new IllegalArgumentException("Unmapped target columns: " + targetTypes.keySet());
        }
        if (columns.stream().noneMatch(column -> column.name().equals("id"))) {
            throw new IllegalArgumentException("Source id column missing");
        }
        return columns;
    }

    private boolean compatible(String sourceType, int jdbcType, int sourcePrecision, int sourceScale,
                               TargetColumn target, boolean json) {
        String targetType = target.type();
        if (json) {
            return targetType.equals("jsonb");
        }
        return switch (jdbcType) {
            case Types.BIGINT -> targetType.equals("int8");
            case Types.INTEGER -> targetType.equals("int4");
            case Types.VARCHAR, Types.LONGVARCHAR, Types.CHAR ->
                    targetType.equals("text") || ((targetType.equals("varchar") || targetType.equals("bpchar"))
                            && (target.precision() == 0 || sourcePrecision <= target.precision()));
            case Types.DECIMAL, Types.NUMERIC -> targetType.equals("numeric")
                    && (target.precision() == 0 || (sourceScale <= target.scale()
                    && sourcePrecision - sourceScale <= target.precision() - target.scale()));
            case Types.TIMESTAMP -> targetType.equals("timestamp") && sourceScale <= target.scale();
            default -> false;
        };
    }

    private int writeRows(Connection pg, CopyTable table, List<Column> columns,
                          ResultSet rows, UUID taskId) throws SQLException {
        String names = String.join(", ", columns.stream().map(c -> "\"" + c.name() + "\"").toList());
        String values = String.join(", ", columns.stream().map(c -> c.json() ? "?::jsonb" : "?").toList());
        String updates = String.join(", ", columns.stream()
                .filter(c -> !c.name().equals("id"))
                .map(c -> "\"" + c.name() + "\" = EXCLUDED.\"" + c.name() + "\"").toList());
        String upsert = "INSERT INTO " + table.targetQualifiedName() + " (" + names + ") VALUES (" + values
                + ") ON CONFLICT (\"id\") " + (updates.isEmpty() ? "DO NOTHING" : "DO UPDATE SET " + updates);
        int count = 0;
        Long lastId = null;
        try (PreparedStatement write = pg.prepareStatement(upsert)) {
            while (rows.next()) {
                for (int i = 0; i < columns.size(); i++) {
                    Column column = columns.get(i);
                    if (column.json()) {
                        write.setString(i + 1, rows.getString(i + 1));
                    } else if (column.temporal()) {
                        write.setObject(i + 1, rows.getObject(i + 1, LocalDateTime.class));
                    } else {
                        write.setObject(i + 1, rows.getObject(i + 1));
                    }
                    if (column.name().equals("id")) {
                        lastId = rows.getLong(i + 1);
                    }
                }
                write.addBatch();
                count++;
            }
            if (count > 0) {
                write.executeBatch();
            }
        }
        try (var update = pg.prepareStatement("""
                UPDATE databridge_meta.full_checkpoint
                SET last_id = COALESCE(?, last_id), completed = ?, updated_at = now()
                WHERE task_id = ?
                """)) {
            update.setObject(1, lastId);
            update.setBoolean(2, count == 0);
            update.setObject(3, taskId);
            if (update.executeUpdate() != 1) {
                throw new IllegalStateException("Checkpoint update lost for task " + taskId);
            }
        }
        return count;
    }

    private record Checkpoint(Long highWatermark, Long lastId, boolean completed) { }
    private record Column(String name, boolean json, boolean temporal) { }
    private record TargetColumn(String type, int precision, int scale) { }
}
