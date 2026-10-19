package com.databridge.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.Test;

class CdcRecordMapperTest {
    private static final Schema KEY = SchemaBuilder.struct().field("id", Schema.INT64_SCHEMA).build();
    private static final Schema SOURCE = SchemaBuilder.struct()
            .field("db", Schema.STRING_SCHEMA)
            .field("table", Schema.STRING_SCHEMA)
            .build();
    private static final Schema ROW = SchemaBuilder.struct().optional()
            .field("id", Schema.INT64_SCHEMA)
            .field("name", Schema.OPTIONAL_STRING_SCHEMA)
            .build();
    private static final Schema VALUE = SchemaBuilder.struct()
            .field("op", Schema.STRING_SCHEMA)
            .field("source", SOURCE)
            .field("after", ROW)
            .build();
    private final CdcRecordMapper mapper = new CdcRecordMapper();

    @Test
    void createAndUpdateCarryFinalRowValue() throws Exception {
        for (String operation : new String[] {"c", "u"}) {
            var event = mapper.map(record(operation, 7L, "新值"));
            assertEquals("databridge_source.orders", event.tableName());
            assertEquals(operation, event.operation());
            assertEquals(7L, event.rowId());
            assertEquals("新值", new ObjectMapper().readTree(event.afterRowJson()).get("name").asText());
            assertEquals(EventKey.sha256(
                    Map.of("server", "test"), Map.of("file", "mysql-bin.000001", "pos", 120L, "row", 1),
                    "databridge_source.orders", operation, 7L), event.eventKey());
        }
    }

    @Test
    void deleteHasNoAfterRow() {
        var event = mapper.map(record("d", 7L, null));
        assertEquals("d", event.operation());
        assertNull(event.afterRowJson());
    }

    @Test
    void nullableColumnStaysJsonNull() throws Exception {
        var original = record("c", 7L, "value");
        var value = new Struct(VALUE)
                .put("op", "c")
                .put("source", new Struct(SOURCE).put("db", "databridge_source").put("table", "orders"))
                .put("after", new Struct(ROW).put("id", 7L).put("name", null));
        var withNull = new SourceRecord(original.sourcePartition(), original.sourceOffset(),
                original.topic(), KEY, original.key(), VALUE, value);

        var after = new ObjectMapper().readTree(mapper.map(withNull).afterRowJson());
        assertTrue(after.has("name"));
        assertTrue(after.get("name").isNull());
    }

    @Test
    void unknownOperationAndMissingKeyAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> mapper.map(record("x", 7L, "value")));
        var valid = record("c", 7L, "value");
        var missingKey = new SourceRecord(valid.sourcePartition(), valid.sourceOffset(),
                valid.topic(), valid.keySchema(), null, valid.valueSchema(), valid.value());
        assertThrows(IllegalArgumentException.class, () -> mapper.map(missingKey));
    }

    private SourceRecord record(String operation, long id, String name) {
        var source = new Struct(SOURCE).put("db", "databridge_source").put("table", "orders");
        Struct after = name == null ? null : new Struct(ROW).put("id", id).put("name", name);
        var value = new Struct(VALUE).put("op", operation).put("source", source).put("after", after);
        return new SourceRecord(
                Map.of("server", "test"),
                Map.of("file", "mysql-bin.000001", "pos", 120L, "row", 1),
                "test.databridge_source.orders",
                KEY, new Struct(KEY).put("id", id),
                VALUE, value);
    }
}
