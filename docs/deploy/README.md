# 部署（记忆层基础设施）

Mio 的记忆基础设施 = **PostgreSQL + pgvector（长期记忆/唯一真相源） + Redis（短期记忆）**，运行在阿里云 ECS 上，Docker 部署。MinIO/OSS 预留为对象存储扩展接缝，当前不启用（触发信号：二进制资源 / 高频导出分发 / GB 级总量）。

## 快速开始

```bash
cd docs/deploy
cp .env.example .env   # 填写两个密码
docker compose up -d
```

```bash
# .env(本目录,勿提交;真实密码由部署者保管)
PG_PASSWORD=你的PG密码
REDIS_PASSWORD=你的Redis密码
```

> 现有 ECS 实例是 `docker run` 手工部署的，与本 compose 等价；如需迁移到 compose 管理，注意数据卷路径一致（`/data/postgres`、`/data/redis`)，先 `docker stop` 旧容器再 `compose up`，数据不丢。

## 组件说明

| 组件 | 镜像 | 端口 | 数据卷 | 关键参数 |
|---|---|---|---|---|
| PostgreSQL | `pgvector/pgvector:pg16` | 5432 | `/data/postgres` | 库 `mio`，应用启动时幂等建表（见 `docs/db/schema.sql`) |
| Redis | `redis:7.4-alpine` | 6379 | `/data/redis` | AOF everysec；`maxmemory 256mb` + `allkeys-lru`（所有 key 可弃，Redis 无唯一副本） |

## 安全基线（硬性要求）

1. **安全组必须配白名单**:5432/6379 仅放行可信 IP(`/32`)，禁止 `0.0.0.0/0`。Redis 公网裸奔是经典入侵路径，即使有密码也不例外。
2. 若应用与本栈同机部署：端口改绑 `127.0.0.1`(`- "127.0.0.1:5432:5432"`)，安全组两个端口都关掉。
3. 密码集中保管在 `.env`，不进入任何 URL 形式（Redis 密码含 `@` 时 URI 需编码为 `%40`，表单项不受影响）。
4. 凭证在聊天记录/日志中出现过的，安排轮换。

## 验证

```bash
docker exec postgres psql -U root -d mio -c 'SELECT count(*) FROM events;'
docker exec redis redis-cli -a "$REDIS_PASSWORD" ping   # 期望 PONG
```

## 故障方向约定

- Redis 不可达：应用 fail-open，回退进程内存（重启丢会话），不阻断聊天。
- PG 不可达：画像/回忆降级（fail-open)；信任/自动化倒向全部人工确认（fail-closed)。
- 备份：PG 是唯一真相源，建议后续加 `pg_dump` 定时任务（roadmap 项）。
