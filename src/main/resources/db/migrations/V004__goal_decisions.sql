CREATE TABLE IF NOT EXISTS goal_comparisons (
    id TEXT PRIMARY KEY,
    goal_id TEXT NOT NULL REFERENCES goals(id),
    program_revision BIGINT NOT NULL,
    goal_revision BIGINT NOT NULL,
    evidence_hash TEXT NOT NULL,
    request TEXT NOT NULL,
    result TEXT NOT NULL,
    created_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_goal_comparisons_recent ON goal_comparisons(goal_id,created_at DESC);
