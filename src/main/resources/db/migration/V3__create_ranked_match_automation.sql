CREATE TABLE tracked_player_automation (
    id UUID PRIMARY KEY,
    puuid VARCHAR(100) NOT NULL UNIQUE,
    enabled BOOLEAN NOT NULL,
    last_seen_match_id VARCHAR(100),
    last_checked_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_tracked_player_automation_due
    ON tracked_player_automation (last_checked_at, created_at)
    WHERE enabled = TRUE;

CREATE TABLE automation_execution (
    id UUID PRIMARY KEY,
    automation_id UUID NOT NULL REFERENCES tracked_player_automation (id),
    detected_match_id VARCHAR(100) NOT NULL,
    analysis_job_id UUID REFERENCES analysis_job (id),
    status VARCHAR(20) NOT NULL,
    detected_at TIMESTAMP WITH TIME ZONE NOT NULL,
    triggered_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_automation_execution_detected_match UNIQUE (automation_id, detected_match_id),
    CONSTRAINT uq_automation_execution_analysis_job UNIQUE (analysis_job_id),
    CONSTRAINT ck_automation_execution_state CHECK (
        (status = 'CLAIMED'
            AND analysis_job_id IS NULL
            AND triggered_at IS NULL)
        OR (status = 'JOB_CREATED'
            AND analysis_job_id IS NOT NULL
            AND triggered_at IS NULL)
        OR (status = 'TRIGGERED'
            AND analysis_job_id IS NOT NULL
            AND triggered_at IS NOT NULL)
    )
);
