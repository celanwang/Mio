CREATE TABLE IF NOT EXISTS document_index_jobs (
    id TEXT PRIMARY KEY,
    document_id TEXT NOT NULL REFERENCES workspace_documents(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL,
    namespace TEXT NOT NULL,
    state TEXT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    lease_token TEXT,
    lease_until BIGINT NOT NULL DEFAULT 0,
    next_at BIGINT NOT NULL DEFAULT 0,
    last_error TEXT NOT NULL DEFAULT '',
    updated_at BIGINT NOT NULL,
    UNIQUE(document_id,revision,namespace)
);
CREATE INDEX IF NOT EXISTS idx_document_index_due ON document_index_jobs(state,next_at,lease_until);
