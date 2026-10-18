CREATE SCHEMA IF NOT EXISTS databridge_meta;

CREATE TABLE databridge_meta.cdc_event (
    sequence BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id UUID NOT NULL,
    event_key CHAR(64) NOT NULL,
    source_partition JSONB NOT NULL,
    source_offset JSONB NOT NULL,
    table_name TEXT NOT NULL,
    operation CHAR(1) NOT NULL CHECK (operation IN ('c', 'u', 'd')),
    row_id BIGINT NOT NULL,
    after_row JSONB,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT cdc_event_task_key UNIQUE (task_id, event_key),
    CONSTRAINT cdc_event_after_row CHECK (
        (operation = 'd' AND after_row IS NULL)
        OR (operation IN ('c', 'u') AND after_row IS NOT NULL)
    )
);

CREATE INDEX cdc_event_task_sequence ON databridge_meta.cdc_event (task_id, sequence);
