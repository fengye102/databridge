package com.databridge.copy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CopyTableTest {
    @Test
    void qualifiesEachDatabaseIdentifier() {
        var table = new CopyTable("source_db", "orders_1", "business", "orders_1");
        assertEquals("`source_db`.`orders_1`", table.sourceQualifiedName());
        assertEquals("\"business\".\"orders_1\"", table.targetQualifiedName());
    }

    @Test
    void rejectsUnsafeIdentifiers() {
        for (var name : new String[] {"", "orders.other", "orders name", "orders\"", "1orders"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new CopyTable("source_db", name, "business", "orders"));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new CopyTable(null, "orders", "business", "orders"));
    }
}
