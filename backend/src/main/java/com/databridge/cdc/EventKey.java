package com.databridge.cdc;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public final class EventKey {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private EventKey() {
    }

    public static String sha256(
            Map<String, ?> partition, Map<String, ?> offset,
            String tableName, String operation, long rowId) {
        if (partition == null || partition.isEmpty() || offset == null || offset.isEmpty()
                || tableName == null || tableName.isBlank()
                || operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("Event identity requires partition, offset, table and operation");
        }
        try {
            byte[] identity = JSON.writeValueAsBytes(List.of(
                    partition, offset, tableName, operation, rowId));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity));
        } catch (NoSuchAlgorithmException | java.io.IOException e) {
            throw new IllegalStateException("Cannot compute event identity", e);
        }
    }
}
