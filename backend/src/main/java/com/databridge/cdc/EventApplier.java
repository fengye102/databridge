package com.databridge.cdc;

import com.databridge.copy.CopyTable;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

public final class EventApplier {
    private final DataSource target;
    private final JsonMapper json = new JsonMapper();

    public EventApplier(DataSource target) {
        this.target = java.util.Objects.requireNonNull(target);
    }

    public int applyNextBatch(UUID taskId, CopyTable table, int batchSize) {
        java.util.Objects.requireNonNull(taskId);
        java.util.Objects.requireNonNull(table);
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        try (var pg = target.getConnection()) {
            pg.setAutoCommit(false);
            try {
                requireCopyComplete(pg, taskId);
                long cursor = lockCursor(pg, taskId);
                List<Event> events = readEvents(pg, taskId, cursor, batchSize);
                if (events.isEmpty()) {
                    pg.commit();
                    return 0;
                }
                List<Column> columns = inspectTarget(pg, table);
                for (Event event : events) {
                    if (event.tableName().equals(table.sourceSchema() + ".sync_probe")) {
                        continue;
                    }
                    if (!event.tableName().equals(table.sourceSchema() + "." + table.sourceTable())) {
                        throw new IllegalArgumentException("Event belongs to a different source table: "
                                + event.tableName());
                    }
                    switch (event.operation()) {
                        case "c", "u" -> upsert(pg, table, columns, event);
                        case "d" -> delete(pg, table, event);
                        default -> throw new IllegalArgumentException("Unsupported CDC operation: "
                                + event.operation());
                    }
                }
                try (var update = pg.prepareStatement("""
                        UPDATE databridge_meta.event_cursor
                        SET last_sequence = ?, updated_at = now() WHERE task_id = ?
                        """)) {
                    update.setLong(1, events.getLast().sequence());
                    update.setObject(2, taskId);
                    if (update.executeUpdate() != 1) {
                        throw new IllegalStateException("Event cursor disappeared for task " + taskId);
                    }
                }
                pg.commit();
                return events.size();
            } catch (SQLException | RuntimeException e) {
                pg.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Event application failed for task " + taskId, e);
        }
    }

    private void requireCopyComplete(Connection pg, UUID taskId) throws SQLException {
        try (var check = pg.prepareStatement(
                "SELECT completed FROM databridge_meta.full_checkpoint WHERE task_id = ?")) {
            check.setObject(1, taskId);
            try (var result = check.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) {
                    throw new IllegalStateException("Full copy is not complete for task " + taskId);
                }
            }
        }
    }

    private long lockCursor(Connection pg, UUID taskId) throws SQLException {
        try (var insert = pg.prepareStatement("""
                INSERT INTO databridge_meta.event_cursor(task_id) VALUES (?)
                ON CONFLICT (task_id) DO NOTHING
                """)) {
            insert.setObject(1, taskId);
            insert.executeUpdate();
        }
        try (var lock = pg.prepareStatement("""
                SELECT last_sequence FROM databridge_meta.event_cursor
                WHERE task_id = ? FOR UPDATE
                """)) {
            lock.setObject(1, taskId);
            try (var result = lock.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException("Event cursor missing for task " + taskId);
                }
                return result.getLong(1);
            }
        }
    }

    private List<Event> readEvents(Connection pg, UUID taskId, long cursor, int batchSize) throws SQLException {
        var events = new ArrayList<Event>();
        try (var read = pg.prepareStatement("""
                SELECT sequence, table_name, operation, row_id, after_row::text
                FROM databridge_meta.cdc_event
                WHERE task_id = ? AND sequence > ?
                ORDER BY sequence LIMIT ?
                """)) {
            read.setObject(1, taskId);
            read.setLong(2, cursor);
            read.setInt(3, batchSize);
            try (var result = read.executeQuery()) {
                while (result.next()) {
                    events.add(new Event(result.getLong(1), result.getString(2), result.getString(3),
                            result.getLong(4), result.getString(5)));
                }
            }
        }
        return events;
    }

    private List<Column> inspectTarget(Connection pg, CopyTable table) throws SQLException {
        var columns = new ArrayList<Column>();
        try (var statement = pg.createStatement();
             var result = statement.executeQuery("SELECT * FROM " + table.targetQualifiedName() + " WHERE 1 = 0")) {
            var metadata = result.getMetaData();
            for (int i = 1; i <= metadata.getColumnCount(); i++) {
                String name = metadata.getColumnName(i);
                if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    throw new IllegalArgumentException("Unsupported target column identifier: " + name);
                }
                String type = metadata.getColumnTypeName(i).toLowerCase(Locale.ROOT);
                if (!Set.of("int8", "int4", "varchar", "bpchar", "text", "numeric", "jsonb", "timestamp")
                        .contains(type)) {
                    throw new IllegalArgumentException("Unsupported target column type: " + name + " " + type);
                }
                columns.add(new Column(name, type, metadata.getPrecision(i), metadata.getScale(i)));
            }
        }
        if (columns.stream().noneMatch(column -> column.name().equals("id") && column.type().equals("int8"))) {
            throw new IllegalArgumentException("Target requires signed BIGINT id");
        }
        return columns;
    }

    private void upsert(Connection pg, CopyTable table, List<Column> columns, Event event) throws SQLException {
        JsonNode row = parseRow(event.afterJson());
        if (!row.isObject() || row.size() != columns.size()) {
            throw new IllegalArgumentException("CDC after_row must contain every target column");
        }
        Set<String> known = new HashSet<>();
        for (Column column : columns) {
            known.add(column.name());
            if (!row.has(column.name())) {
                throw new IllegalArgumentException("CDC after_row lacks column " + column.name());
            }
        }
        row.fieldNames().forEachRemaining(name -> {
            if (!known.contains(name)) {
                throw new IllegalArgumentException("CDC after_row has unknown column " + name);
            }
        });
        JsonNode id = row.get("id");
        if (!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() != event.rowId()) {
            throw new IllegalArgumentException("CDC after_row id differs from event key");
        }
        String names = String.join(", ", columns.stream().map(c -> "\"" + c.name() + "\"").toList());
        String values = String.join(", ", columns.stream().map(c -> c.type().equals("jsonb")
                ? "?::jsonb" : "?").toList());
        String updates = String.join(", ", columns.stream().filter(c -> !c.name().equals("id"))
                .map(c -> "\"" + c.name() + "\" = EXCLUDED.\"" + c.name() + "\"").toList());
        String sql = "INSERT INTO " + table.targetQualifiedName() + " (" + names + ") VALUES (" + values
                + ") ON CONFLICT (\"id\") " + (updates.isEmpty() ? "DO NOTHING" : "DO UPDATE SET " + updates);
        try (var statement = pg.prepareStatement(sql)) {
            for (int i = 0; i < columns.size(); i++) {
                Column column = columns.get(i);
                statement.setObject(i + 1, convert(row.get(column.name()), column));
            }
            statement.executeUpdate();
        }
    }

    private JsonNode parseRow(String afterJson) {
        if (afterJson == null) {
            throw new IllegalArgumentException("CDC upsert lacks after_row");
        }
        try {
            return json.readTree(afterJson);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid CDC after_row JSON", e);
        }
    }

    private Object convert(JsonNode value, Column column) {
        if (value.isNull()) {
            return null;
        }
        return switch (column.type()) {
            case "int8" -> {
                if (!value.isIntegralNumber() || !value.canConvertToLong()) {
                    throw new IllegalArgumentException("Invalid BIGINT: " + column.name());
                }
                yield value.longValue();
            }
            case "int4" -> {
                if (!value.isIntegralNumber() || !value.canConvertToInt()) {
                    throw new IllegalArgumentException("Invalid INTEGER: " + column.name());
                }
                yield value.intValue();
            }
            case "varchar", "bpchar", "text" -> {
                if (!value.isTextual() || (column.precision() > 0 && !column.type().equals("text")
                        && value.textValue().length() > column.precision())) {
                    throw new IllegalArgumentException("Invalid text: " + column.name());
                }
                yield value.textValue();
            }
            default -> convertOther(value, column);
        };
    }

    private Object convertOther(JsonNode value, Column column) {
        return switch (column.type()) {
            case "numeric" -> {
                if (!value.isTextual() && !value.isNumber()) {
                    throw new IllegalArgumentException("Invalid DECIMAL: " + column.name());
                }
                BigDecimal decimal = new BigDecimal(value.asText());
                if (column.precision() > 0 && (decimal.scale() > column.scale()
                        || decimal.precision() - decimal.scale() > column.precision() - column.scale())) {
                    throw new IllegalArgumentException("DECIMAL exceeds target precision: " + column.name());
                }
                yield decimal;
            }
            case "jsonb" -> value.isTextual() ? value.textValue() : value.toString();
            case "timestamp" -> {
                if (!value.isTextual()) {
                    throw new IllegalArgumentException("Invalid timestamp: " + column.name());
                }
                String text = value.textValue();
                yield text.endsWith("Z") || text.matches(".*[+-]\\d\\d:\\d\\d$")
                        ? OffsetDateTime.parse(text).atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()
                        : LocalDateTime.parse(text);
            }
            default -> throw new IllegalArgumentException("Unsupported target column type: " + column.type());
        };
    }

    private void delete(Connection pg, CopyTable table, Event event) throws SQLException {
        if (event.afterJson() != null) {
            throw new IllegalArgumentException("CDC delete must not contain after_row");
        }
        try (var statement = pg.prepareStatement(
                "DELETE FROM " + table.targetQualifiedName() + " WHERE \"id\" = ?")) {
            statement.setLong(1, event.rowId());
            statement.executeUpdate();
        }
    }

    private record Event(long sequence, String tableName, String operation, long rowId, String afterJson) { }
    private record Column(String name, String type, int precision, int scale) { }
}
