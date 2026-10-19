package com.databridge.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RowCanonicalizerTest {
    @Test
    void normalizesDecimalJsonUnicodeAndTimeWithoutChangingNull() {
        assertEquals("1.2", RowCanonicalizer.normalize("numeric", new BigDecimal("1.2000")));
        assertEquals("1.2", RowCanonicalizer.normalize("decimal", new BigDecimal("1.20")));
        assertEquals("{\"a\":1,\"b\":[2,3]}",
                RowCanonicalizer.normalize("jsonb", "{\"b\":[2.0,3],\"a\":1.00}"));
        assertEquals("{\"a\":1,\"b\":[2,3]}",
                RowCanonicalizer.normalize("json", "{\"a\":1,\"b\":[2,3]}"));
        assertEquals("é", RowCanonicalizer.normalize("text", "e\u0301"));
        assertEquals("2026-10-19T12:00:00.123", RowCanonicalizer.normalize(
                "timestamp", LocalDateTime.of(2026, 10, 19, 12, 0, 0, 123_000_000)));
        assertEquals(null, RowCanonicalizer.normalize("text", null));
    }

    @Test
    void rowFingerprintIgnoresColumnOrderButDetectsChangedValue() {
        String first = RowCanonicalizer.fingerprint(Map.of("id", "1", "name", "a"));
        assertEquals(first, RowCanonicalizer.fingerprint(Map.of("name", "a", "id", "1")));
        assertNotEquals(first, RowCanonicalizer.fingerprint(Map.of("id", "1", "name", "b")));
    }
}
