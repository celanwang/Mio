INSERT INTO document_chunks(document_id,revision,start_offset,end_offset,content) SELECT d.id,d.revision,o,least(o+1200,length(d.content)),substring(d.content from o+1 for 1200) FROM workspace_documents d CROSS JOIN LATERAL generate_series(0,greatest(length(d.content)-1,0),1050) o ON CONFLICT DO NOTHING;
CREATE TABLE IF NOT EXISTS resource_watches(operation_id TEXT PRIMARY KEY REFERENCES goal_resources(operation_id),enabled BOOLEAN NOT NULL DEFAULT true);
CREATE TABLE IF NOT EXISTS forgotten_episodes(id TEXT PRIMARY KEY,forgotten_at BIGINT NOT NULL);
