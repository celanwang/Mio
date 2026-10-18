-- Read-only export from existing events; no schema change or side-effect replay.
-- psql "$EVAL_PG_URL" -X -qAt -f docs/evaluation/export-events.sql > /private/path/events.jsonl
-- Use a read-only DB role. This exports private dialog-adjacent data; keep output outside git.
BEGIN READ ONLY;
SELECT json_build_object('id', id, 'session_id', session_id, 'ts', ts,
                         'node', node, 'type', type, 'title', title, 'detail', detail)::text
FROM events
WHERE type IN ('policy_decision', 'tool_result', 'confirm')
  AND ts >= EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - INTERVAL '7 days')) * 1000
  AND session_id IS NOT NULL
ORDER BY ts, id;
ROLLBACK;
