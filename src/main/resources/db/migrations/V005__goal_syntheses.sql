CREATE TABLE IF NOT EXISTS goal_syntheses (
    id TEXT PRIMARY KEY,
    goal_id TEXT NOT NULL REFERENCES goals(id),
    source_hash TEXT NOT NULL,
    content TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    UNIQUE(goal_id,source_hash)
);
