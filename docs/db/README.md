# 数据库设计（PostgreSQL + pgvector）

PG 是 Mio **长期记忆的唯一真相源**；短期记忆（会话上下文、去重、缓存）在 Redis，见 `docs/deploy/`。本地不落任何记忆数据文件（凭证类文件如 `.env`、`.weibo/auth.json` 属配置/凭证，不在此约束内）。

## 表总览

| 表 | 职责 | 性质 |
|---|---|---|
| `events` | 事件日志（对话/工具/审核/注入决策） | **真相源**,append-only |
| `messages` | 对话消息原文 | **真相源** |
| `embeddings` | 消息向量（text-embedding-v4,1024 维） | 派生态，可随时重建 |
| `entities` | 实体三元组（双时态） | 蒸馏产物，为意图消歧打底 |
| `profiles` | 用户画像页（Markdown 逻辑文档） | 蒸馏视图，可重放重建 |
| `trusts` | 工具调用的人工批准/拒绝统计 | fail-closed，只统计不放行 |
| `automations` | 自动化规则状态机（PROPOSED→ACTIVE/REJECTED） | fail-closed，须用户批准 |

领域无关：新场景（打车、微博……）接入零表变更。画像按 `profiles.scope` 分域，工具相关表按 `tool_name` 区分；**不要按领域建表**。

## 设计规则

1. 表名单词复数；主键统一 `id`;`TRIGGER` 等保留字不作列名（故 `trigger_kind`)。
2. 字段类型只用 `TEXT` / `BIGINT`（毫秒时间戳）/ `INTEGER` / `DOUBLE PRECISION` / `TEXT[]`。
3. 无外键，一致性由应用层保证；全部 `CREATE TABLE IF NOT EXISTS` 幂等自建（`PgMemoryConfig`)，无 Flyway。
4. **派生态与真相源物理隔离**:`embeddings` 独立成表，`TRUNCATE` + 异步重灌即完成向量重建。
5. 溯源：`profiles.source_messages` 记录画像页的证据消息 id（Memory as File 思想的 Sources 契约在 PG 的落点）。

## 变更纪律

- `events` 只允许追加字段，禁止改名/删列（为未来 MQ 重放留地基）。
- 部署初期允许 DROP 重建；数据进入积累期后一律走 `ALTER`。
- 向量索引（HNSW/IVFFlat）在 5 万条以下不建，暴力余弦足够。

完整 DDL 见 [schema.sql](schema.sql)。
