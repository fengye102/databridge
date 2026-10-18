CREATE TABLE databridge_meta.full_checkpoint (
    task_id UUID PRIMARY KEY,
    high_watermark BIGINT,
    last_id BIGINT,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT full_checkpoint_empty_state CHECK (
        high_watermark IS NOT NULL OR (last_id IS NULL AND completed)
    ),
    CONSTRAINT full_checkpoint_progress CHECK (
        last_id IS NULL OR (high_watermark IS NOT NULL AND last_id <= high_watermark)
    )
);
