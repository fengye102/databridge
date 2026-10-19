package com.databridge.cdc;

public record CdcEvent(
        String eventKey,
        String sourcePartitionJson,
        String sourceOffsetJson,
        String tableName,
        String operation,
        long rowId,
        String afterRowJson) {
}
