-- Mio 数据库 Schema 定稿(PostgreSQL 16 + pgvector)
--
-- 定位:PG 是长期记忆的唯一真相源;本地不落任何记忆数据文件。
-- 设计规则:
--   1. 表名统一单词复数,说业务不说隐喻;
--   2. 主键统一 id(TEXT 业务指纹 / BIGSERIAL 纯序号);
--   3. 字段类型只用 TEXT / BIGINT(毫秒时间戳)/ INTEGER / DOUBLE PRECISION / TEXT[];
--   4. 不留"也许有用"的字段,每列当下有消费者或有明确排期;
--   5. 无外键,一致性由应用层保证;全部 CREATE TABLE IF NOT EXISTS 幂等自建(见 PgMemoryConfig)。
--
-- 部署初期允许 DROP 重建,不写迁移脚本;进入积累期后变更走 ALTER。

-- 1. 事件日志:唯一真相源,append-only,只许追加字段不许改名(为重放/MQ 留地基)
CREATE TABLE IF NOT EXISTS events (
    id         TEXT PRIMARY KEY,
    ts         BIGINT NOT NULL,
    session_id TEXT,
    node       TEXT,
    type       TEXT,
    title      TEXT,
    detail     TEXT
);
CREATE INDEX IF NOT EXISTS idx_events_type_ts ON events(type, ts);

-- 2. 对话消息:user/assistant 原文,长期记忆的原始素材
CREATE TABLE IF NOT EXISTS messages (
    id         TEXT PRIMARY KEY,           -- 新消息为 msg-v2- + SHA-256(session + SDK message id)，历史指纹保留
    session_id TEXT,
    ts         BIGINT NOT NULL,
    role       TEXT NOT NULL,              -- user / assistant
    text       TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(session_id);
CREATE INDEX IF NOT EXISTS idx_messages_ts ON messages(ts);

-- 3. 消息向量:派生态；清空或更换模型需显式重建迁移，不自动重置已完成作业。
CREATE TABLE IF NOT EXISTS embeddings (
    id        TEXT PRIMARY KEY,            -- 与 messages.id 同值
    embedding vector(1024) NOT NULL        -- DashScope text-embedding-v4,维度 1024
);

-- 4. 实体三元组:双时态,KG 的 Lv0 形态,为意图消歧打底
CREATE TABLE IF NOT EXISTS entities (
    id         BIGSERIAL PRIMARY KEY,
    subject    TEXT NOT NULL,
    predicate  TEXT NOT NULL,
    object     TEXT NOT NULL,
    valid_from BIGINT NOT NULL,
    valid_to   BIGINT,
    source_ts  BIGINT NOT NULL
);
ALTER TABLE entities ADD COLUMN IF NOT EXISTS source_message_id TEXT;
ALTER TABLE entities ADD COLUMN IF NOT EXISTS derivation_key TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS idx_entities_derivation ON entities(derivation_key) WHERE derivation_key IS NOT NULL;

-- 5. 画像页:蒸馏视图,content 即 Markdown 逻辑文档(Memory as File 的 PG 表达)
CREATE TABLE IF NOT EXISTS profiles (
    id              TEXT PRIMARY KEY,      -- 页面标识(原 slug),创建后不变
    scope           TEXT NOT NULL,         -- global / mcd-ordering / tencent-ride / weibo-assistant
    title           TEXT NOT NULL,
    source          TEXT NOT NULL,         -- explicit(手动)/ inferred(提炼器)
    confidence      DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    content         TEXT NOT NULL,         -- Markdown 正文
    source_messages TEXT[],                -- 兼容保留；当前来源通过作业检查点与 source_position 关联
    created_at      BIGINT,
    updated_at      BIGINT NOT NULL,
    revision        BIGINT NOT NULL DEFAULT 0,
    deleted_at      BIGINT                  -- 删除标记只保留身份，阻止自动提炼复活页面
);
ALTER TABLE profiles ADD COLUMN IF NOT EXISTS revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE profiles ADD COLUMN IF NOT EXISTS deleted_at BIGINT;
ALTER TABLE profiles ADD COLUMN IF NOT EXISTS source_position BIGINT NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_profiles_scope ON profiles(scope);

-- 6. 信任统计:只观察统计,不做自动放行;fail-closed,数据只进 PG
CREATE TABLE IF NOT EXISTS trusts (
    tool_name        TEXT NOT NULL,
    param_signature  TEXT NOT NULL,        -- 参数骨架:create-order[addressId,items]
    approvals        INTEGER NOT NULL DEFAULT 0,
    rejections       INTEGER NOT NULL DEFAULT 0,
    last_decision_at BIGINT NOT NULL,
    PRIMARY KEY (tool_name, param_signature)   -- 业务唯一键,UPSERT 累加计数
);

-- 7. 自动化规则:状态机 PROPOSED → ACTIVE / REJECTED,必须用户显式批准才生效
CREATE TABLE IF NOT EXISTS automations (
    id           TEXT PRIMARY KEY,
    title        TEXT NOT NULL,
    tool_name    TEXT NOT NULL,
    trigger_kind TEXT NOT NULL,            -- 现仅 session-start;避开保留字 TRIGGER
    status       TEXT NOT NULL DEFAULT 'PROPOSED'
                 CHECK (status IN ('PROPOSED', 'ACTIVE', 'REJECTED')),
    reason       TEXT NOT NULL,            -- 提议依据说明
    created_at   BIGINT NOT NULL,
    decided_at   BIGINT                    -- PROPOSED 时为 NULL
);
CREATE INDEX IF NOT EXISTS idx_automations_status ON automations(status);

-- 协作运行时（MIO_MULTI_AGENT_ENABLED=true 时由独立初始化器创建）。
-- 关键状态使用 PG，事件与状态同事务；关闭多 Agent 时不影响原有七张表。
CREATE TABLE IF NOT EXISTS goals (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL UNIQUE,
    revision BIGINT NOT NULL,
    content TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS tasks (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL,
    goal_revision BIGINT NOT NULL,
    task_key TEXT NOT NULL,
    revision BIGINT NOT NULL,
    content TEXT NOT NULL,
    UNIQUE(session_id, goal_revision, task_key)
);
CREATE INDEX IF NOT EXISTS idx_tasks_session ON tasks(session_id);
CREATE TABLE IF NOT EXISTS agent_states (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    state_key TEXT NOT NULL,
    content TEXT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    UNIQUE(user_id, session_id, state_key)
);
CREATE INDEX IF NOT EXISTS idx_agent_states_session ON agent_states(session_id);

-- Cross-request budget reservations; completed/unknown model accounting is not reset on restart.
CREATE TABLE IF NOT EXISTS goal_budgets (
    goal_id TEXT PRIMARY KEY REFERENCES goals(id),
    content TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS budget_calls (
    id TEXT PRIMARY KEY,
    goal_id TEXT NOT NULL REFERENCES goal_budgets(goal_id),
    state TEXT NOT NULL,
    tokens BIGINT,
    expires_at BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_budget_calls_goal ON budget_calls(goal_id);
ALTER TABLE budget_calls ADD COLUMN IF NOT EXISTS expires_at BIGINT NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_budget_calls_pending ON budget_calls(expires_at,id) WHERE state='PENDING';

-- Credential-scope uncertainty fence for explicitly bound operations.
CREATE TABLE IF NOT EXISTS operation_scopes(id TEXT PRIMARY KEY);
CREATE TABLE IF NOT EXISTS operation_claims(
    scope_key TEXT NOT NULL REFERENCES operation_scopes(id),
    operation_id TEXT NOT NULL,
    task_id TEXT NOT NULL REFERENCES tasks(id),
    logical_id TEXT NOT NULL UNIQUE,
    PRIMARY KEY(scope_key,operation_id)
);

-- Existing checkpoint envelopes stay unchanged; legacy rows start at revision zero.
ALTER TABLE agent_states ADD COLUMN IF NOT EXISTS revision BIGINT NOT NULL DEFAULT 0;
CREATE TABLE IF NOT EXISTS execution_leases(
    resource TEXT PRIMARY KEY,
    owner TEXT,
    generation BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    session_id TEXT NOT NULL
);

-- Explicit foreground dispatch; claimed-but-not-started may recover, started work is never replayed.
CREATE TABLE IF NOT EXISTS task_queue(
    task_id TEXT PRIMARY KEY REFERENCES tasks(id),
    session_id TEXT NOT NULL,
    state TEXT NOT NULL,
    revision BIGINT NOT NULL,
    content TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_task_queue_session ON task_queue(session_id,state);

-- Goal control changes do not append fake user turns or reset the intent revision/budget.
ALTER TABLE goals ADD COLUMN IF NOT EXISTS control_revision BIGINT NOT NULL DEFAULT 0;

-- User-confirmed acceptance conditions; changes and their audit event commit together.
CREATE TABLE IF NOT EXISTS goal_acceptances(
    goal_id TEXT PRIMARY KEY REFERENCES goals(id),
    revision BIGINT NOT NULL,
    content TEXT NOT NULL
);

-- 原文与派生作业同事务登记；结果检查点不通过诊断 API 返回。
CREATE TABLE IF NOT EXISTS memory_jobs(
    id TEXT PRIMARY KEY, message_id TEXT NOT NULL REFERENCES messages(id), kind TEXT NOT NULL,
    sequence BIGSERIAL UNIQUE, priority INTEGER NOT NULL DEFAULT 1, state TEXT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0, generation BIGINT NOT NULL DEFAULT 0, owner TEXT,
    lease_until BIGINT NOT NULL DEFAULT 0, next_attempt_at BIGINT NOT NULL DEFAULT 0,
    result TEXT, error_code TEXT NOT NULL DEFAULT '', created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_memory_jobs_ready ON memory_jobs(state,next_attempt_at,sequence);
CREATE TABLE IF NOT EXISTS memory_backfills(id TEXT PRIMARY KEY,cursor TEXT NOT NULL DEFAULT '',finished INTEGER NOT NULL DEFAULT 0);
INSERT INTO memory_backfills(id) VALUES ('episode-index-v1') ON CONFLICT DO NOTHING;
CREATE TABLE IF NOT EXISTS memory_job_lanes(id TEXT PRIMARY KEY);
INSERT INTO memory_job_lanes(id) VALUES ('DISTILL') ON CONFLICT DO NOTHING;
CREATE TABLE IF NOT EXISTS memory_usage_days(
    day TEXT PRIMARY KEY,calls INTEGER NOT NULL DEFAULT 0,input_chars BIGINT NOT NULL DEFAULT 0,
    known_tokens BIGINT NOT NULL DEFAULT 0,pending INTEGER NOT NULL DEFAULT 0,unknown INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS memory_usage_calls(
    id TEXT PRIMARY KEY,day TEXT NOT NULL REFERENCES memory_usage_days(day),kind TEXT NOT NULL,
    job_id TEXT,generation BIGINT,state TEXT NOT NULL,tokens BIGINT,created_at BIGINT NOT NULL,expires_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_memory_usage_pending ON memory_usage_calls(state,expires_at);
