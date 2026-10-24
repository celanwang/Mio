CREATE TABLE IF NOT EXISTS goal_source_references (
    id TEXT PRIMARY KEY,
    goal_id TEXT NOT NULL REFERENCES goals(id),
    document_id TEXT NOT NULL REFERENCES workspace_documents(id) ON DELETE CASCADE,
    document_revision BIGINT NOT NULL,
    start_offset INTEGER NOT NULL,
    end_offset INTEGER NOT NULL,
    quote TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    UNIQUE(goal_id,document_id,document_revision,start_offset,end_offset)
);
