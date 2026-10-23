CREATE TABLE IF NOT EXISTS goal_selections (
    id TEXT PRIMARY KEY,
    goal_id TEXT NOT NULL REFERENCES goals(id),
    request_id TEXT NOT NULL,
    program_revision BIGINT NOT NULL,
    goal_revision BIGINT NOT NULL,
    step_id TEXT NOT NULL,
    source_task TEXT NOT NULL REFERENCES tasks(id),
    source_revision BIGINT NOT NULL,
    evidence_hash TEXT NOT NULL,
    comparison_id TEXT,
    content TEXT NOT NULL,
    instruction TEXT NOT NULL,
    state TEXT NOT NULL,
    execution_goal_revision BIGINT,
    execution_key TEXT,
    created_at BIGINT NOT NULL,
    UNIQUE(goal_id,request_id)
);
CREATE INDEX IF NOT EXISTS idx_goal_selections_recent ON goal_selections(goal_id,created_at DESC);
CREATE TABLE IF NOT EXISTS goal_feedback (
    id TEXT PRIMARY KEY,
    goal_id TEXT NOT NULL REFERENCES goals(id),
    selection_id TEXT NOT NULL REFERENCES goal_selections(id),
    request_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    comment TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    UNIQUE(goal_id,request_id)
);
