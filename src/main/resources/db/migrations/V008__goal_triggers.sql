CREATE TABLE IF NOT EXISTS goal_triggers (
    id TEXT PRIMARY KEY,
    goal_id TEXT NOT NULL REFERENCES goals(id),
    program_revision BIGINT NOT NULL,
    goal_revision BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    content TEXT NOT NULL,
    state TEXT NOT NULL,
    activations INTEGER NOT NULL DEFAULT 0,
    failures INTEGER NOT NULL DEFAULT 0,
    last_error TEXT NOT NULL DEFAULT '',
    last_fingerprint TEXT NOT NULL,
    last_fired_at BIGINT NOT NULL DEFAULT 0,
    next_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_goal_triggers_due ON goal_triggers(state,next_at);
