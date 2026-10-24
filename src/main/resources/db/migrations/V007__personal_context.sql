ALTER TABLE profiles ADD COLUMN IF NOT EXISTS expires_at BIGINT;
CREATE TABLE IF NOT EXISTS profile_provenance (
    id TEXT PRIMARY KEY,
    profile_id TEXT NOT NULL REFERENCES profiles(id),
    revision BIGINT NOT NULL,
    kind TEXT NOT NULL,
    message_id TEXT,
    reason TEXT NOT NULL,
    created_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_profile_provenance ON profile_provenance(profile_id,revision DESC);
CREATE TABLE IF NOT EXISTS goal_profile_exclusions (
    goal_id TEXT NOT NULL REFERENCES goals(id),
    profile_id TEXT NOT NULL REFERENCES profiles(id),
    reason TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY(goal_id,profile_id)
);
