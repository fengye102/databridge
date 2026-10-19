CREATE TABLE databridge_meta.verification_run (
    run_id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES databridge_meta.sync_task(task_id),
    status VARCHAR(20) NOT NULL CHECK (status IN ('CONSISTENT', 'DIFFERENT', 'INCONCLUSIVE')),
    source_rows BIGINT,
    target_rows BIGINT,
    difference_rows BIGINT,
    reason_code VARCHAR(60),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT verification_run_counts CHECK (
        (status = 'INCONCLUSIVE' AND source_rows IS NULL AND target_rows IS NULL
            AND difference_rows IS NULL AND reason_code IS NOT NULL)
        OR (status <> 'INCONCLUSIVE' AND source_rows >= 0 AND target_rows >= 0
            AND difference_rows >= 0 AND reason_code IS NULL)
    )
);

CREATE INDEX verification_run_task_time
    ON databridge_meta.verification_run(task_id, completed_at DESC);

CREATE TABLE databridge_meta.verification_diff (
    run_id UUID NOT NULL REFERENCES databridge_meta.verification_run(run_id) ON DELETE CASCADE,
    row_id BIGINT NOT NULL,
    kind VARCHAR(20) NOT NULL CHECK (kind IN
        ('MISSING_TARGET', 'EXTRA_TARGET', 'VALUE_MISMATCH')),
    columns JSONB NOT NULL DEFAULT '[]'::jsonb,
    PRIMARY KEY (run_id, row_id),
    CONSTRAINT verification_diff_columns_array CHECK (jsonb_typeof(columns) = 'array')
);
