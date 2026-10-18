package com.databridge.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EventKeyTest {
    @Test
    void mapInsertionOrderDoesNotChangeIdentity() {
        var firstPartition = new LinkedHashMap<String, Object>();
        firstPartition.put("server", "source");
        firstPartition.put("database", "db");
        var secondPartition = new LinkedHashMap<String, Object>();
        secondPartition.put("database", "db");
        secondPartition.put("server", "source");

        var firstOffset = new LinkedHashMap<String, Object>();
        firstOffset.put("file", "mysql-bin.000001");
        firstOffset.put("pos", 125L);
        firstOffset.put("row", 1);
        var secondOffset = new LinkedHashMap<String, Object>();
        secondOffset.put("row", 1);
        secondOffset.put("pos", 125L);
        secondOffset.put("file", "mysql-bin.000001");

        assertEquals(
                EventKey.sha256(firstPartition, firstOffset, "db.orders", "c", 7L),
                EventKey.sha256(secondPartition, secondOffset, "db.orders", "c", 7L));
    }

    @Test
    void offsetRowOperationAndPrimaryKeyAllParticipate() {
        var partition = Map.<String, Object>of("server", "source");
        var offset = Map.<String, Object>of("file", "mysql-bin.000001", "pos", 125L, "row", 1);
        var baseline = EventKey.sha256(partition, offset, "db.orders", "c", 7L);

        assertNotEquals(baseline, EventKey.sha256(
                partition, Map.of("file", "mysql-bin.000001", "pos", 125L, "row", 2),
                "db.orders", "c", 7L));
        assertNotEquals(baseline, EventKey.sha256(partition, offset, "db.orders", "u", 7L));
        assertNotEquals(baseline, EventKey.sha256(partition, offset, "db.orders", "c", 8L));
        assertNotEquals(baseline, EventKey.sha256(partition, offset, "db.other", "c", 7L));
    }

    @Test
    void missingOffsetIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> EventKey.sha256(Map.of("server", "source"), null, "db.orders", "c", 7L));
    }
}
