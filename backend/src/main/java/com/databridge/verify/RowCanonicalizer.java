package com.databridge.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.TreeMap;

/** Maps supported MySQL/PostgreSQL JDBC values to a stable logical representation. */
final class RowCanonicalizer {
    private static final JsonMapper JSON = new JsonMapper();

    private RowCanonicalizer() { }

    static String normalize(String databaseType, Object value) {
        if (value == null) {
            return null;
        }
        String type = databaseType.toLowerCase(java.util.Locale.ROOT);
        if (type.equals("json") || type.equals("jsonb")) {
            try {
                return canonicalJson(JSON.readTree(value.toString()));
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid JSON column value", e);
            }
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toString();
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toString();
        }
        return Normalizer.normalize(value.toString(), Normalizer.Form.NFC);
    }

    private static String canonicalJson(JsonNode node) throws Exception {
        if (node.isObject()) {
            var fields = new TreeMap<String, String>();
            node.fields().forEachRemaining(field -> {
                try {
                    fields.put(field.getKey(), canonicalJson(field.getValue()));
                } catch (Exception e) {
                    throw new IllegalArgumentException("Invalid JSON object value", e);
                }
            });
            var parts = new java.util.ArrayList<String>();
            for (var field : fields.entrySet()) {
                parts.add(JSON.writeValueAsString(field.getKey()) + ":" + field.getValue());
            }
            return "{" + String.join(",", parts) + "}";
        }
        if (node.isArray()) {
            var parts = new java.util.ArrayList<String>();
            for (JsonNode element : node) {
                parts.add(canonicalJson(element));
            }
            return "[" + String.join(",", parts) + "]";
        }
        if (node.isNumber()) {
            return node.decimalValue().stripTrailingZeros().toPlainString();
        }
        if (node.isTextual()) {
            return JSON.writeValueAsString(Normalizer.normalize(node.textValue(), Normalizer.Form.NFC));
        }
        return node.toString();
    }

    static String fingerprint(Map<String, String> row) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (var field : new TreeMap<>(row).entrySet()) {
                update(digest, field.getKey());
                digest.update((byte) (field.getValue() == null ? 0 : 1));
                if (field.getValue() != null) {
                    update(digest, field.getValue());
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
