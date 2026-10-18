package com.databridge.cdc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.json.JsonConverter;
import org.apache.kafka.connect.source.SourceRecord;

public final class CdcRecordMapper {
    private final JsonMapper json = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();
    private final JsonConverter connectJson = new JsonConverter();

    public CdcRecordMapper() {
        connectJson.configure(Map.of(
                "schemas.enable", false,
                "replace.null.with.default", false), false);
    }

    public CdcEvent map(SourceRecord record) {
        if (!(record.key() instanceof Struct key) || !(record.value() instanceof Struct value)) {
            throw new IllegalArgumentException("CDC record must have a structured key and value");
        }
        if (!(key.get("id") instanceof Long rowId)) {
            throw new IllegalArgumentException("CDC row requires one signed BIGINT id");
        }
        String operation = value.getString("op");
        if (!"c".equals(operation) && !"u".equals(operation) && !"d".equals(operation)) {
            throw new IllegalArgumentException("Unsupported CDC operation: " + operation);
        }
        Struct source = value.getStruct("source");
        if (source == null || source.getString("db") == null || source.getString("table") == null) {
            throw new IllegalArgumentException("CDC source database and table are required");
        }
        String tableName = source.getString("db") + "." + source.getString("table");
        Struct after = value.getStruct("after");
        if (("d".equals(operation) && after != null) || (!"d".equals(operation) && after == null)) {
            throw new IllegalArgumentException("CDC after row does not match operation");
        }
        try {
            String afterRowJson = after == null ? null : new String(
                    connectJson.fromConnectData(record.topic(), after.schema(), after),
                    StandardCharsets.UTF_8);
            return new CdcEvent(
                    EventKey.sha256(record.sourcePartition(), record.sourceOffset(),
                            tableName, operation, rowId),
                    json.writeValueAsString(record.sourcePartition()),
                    json.writeValueAsString(record.sourceOffset()),
                    tableName, operation, rowId, afterRowJson);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("CDC source metadata is not JSON serializable", e);
        }
    }
}
