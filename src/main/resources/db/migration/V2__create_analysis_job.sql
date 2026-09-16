CREATE TABLE analysis_job (
    id UUID PRIMARY KEY,
    status VARCHAR(20) NOT NULL,
    result JSONB,
    failure_code VARCHAR(50),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_analysis_job_state CHECK (
        (status = 'PENDING'
            AND result IS NULL
            AND failure_code IS NULL
            AND started_at IS NULL
            AND completed_at IS NULL)
        OR (status = 'RUNNING'
            AND result IS NULL
            AND failure_code IS NULL
            AND started_at IS NOT NULL
            AND completed_at IS NULL)
        OR (status = 'SUCCEEDED'
            AND result IS NOT NULL
            AND failure_code IS NULL
            AND started_at IS NOT NULL
            AND completed_at IS NOT NULL)
        OR (status = 'FAILED'
            AND result IS NULL
            AND failure_code IS NOT NULL
            AND completed_at IS NOT NULL)
    )
);
