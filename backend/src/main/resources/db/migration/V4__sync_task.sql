CREATE TABLE databridge_meta.sync_task (
    task_id UUID PRIMARY KEY,
    source_schema TEXT NOT NULL,
    source_table TEXT NOT NULL,
    target_schema TEXT NOT NULL,
    target_table TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'CREATED',
    resume_status VARCHAR(20),
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT sync_task_status CHECK (status IN (
        'CREATED', 'CAPTURING', 'COPYING', 'REPLAYING', 'STREAMING',
        'PAUSED', 'STOPPED', 'FAILED'
    )),
    CONSTRAINT sync_task_resume_status CHECK (resume_status IS NULL OR resume_status IN (
        'CAPTURING', 'COPYING', 'REPLAYING', 'STREAMING'
    )),
    CONSTRAINT sync_task_target_unique UNIQUE (target_schema, target_table)
);
