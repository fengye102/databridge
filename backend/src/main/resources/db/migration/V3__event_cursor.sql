CREATE TABLE databridge_meta.event_cursor (
    task_id UUID PRIMARY KEY,
    last_sequence BIGINT NOT NULL DEFAULT 0 CHECK (last_sequence >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
