# Mio

一个 **Personal Agent** 的探索与实践：不止于"会聊天"，而是能记住你、理解你的习惯、在你授权的边界内主动为你做事的个人智能体。

基于 Java 17 + Spring Boot + AgentScope Java（DashScope 模型）。麦当劳点餐是第一个落地场景（通过 MCP 接入），用于验证记忆、权限、审核这条主链路；架构按多场景泛化设计，接入新业务 = 新增一个技能包，内核不变。

## 设计原则

1. **事件流是唯一真相源**：对话、工具调用、审核判定、人工批准全部进 append-only 事件日志；画像、信任、自动化规则都是事件流上可重建的蒸馏视图，系统永远可以重算。
2. **每层有明确的失败方向**：画像损坏 fail-open（降级为不贴心，不阻断对话）；信任与自动化规则损坏 fail-closed（倒向更多人工确认，永不倒向更多放行）。
3. **学习产生候选，授权来自用户**：系统可以提炼偏好、统计习惯、提出自动化建议，但任何放行权都由用户显式授予；记忆注入一律标注为参考信息而非指令。
4. **本地优先，接缝留给未来**：单用户数据全部落盘本地 JSON/JSONL（人类可读、可审计、可手改），不引入数据库；在事件发布、异步任务、存储后端等关键位置预留接口，向量检索、MQ、云服务按真实触发信号逐个接入，不提前建设。

## 架构总览

```
蒸馏层    用户画像（可重建的物化视图）   信任规则（策略）      自动化规则（授权）
             ↑ 提炼                        ↑ 统计晋升            ↑ 习惯检测
记忆层    长期：episodes.jsonl + events-*.jsonl（append-only，按月滚动）
             ↑ 巩固
          短期：会话内上下文（内存）
事件源    对话消息 / 工具调用 / Jev 判定 / 人工批准·拒绝
```

- **画像三层作用域**：`global`（跨场景恒真，全量注入）/ `domains/{domain}`（领域隔离，按当前消息相关性注入）/ 会话级例外。晋升单向，信任规则绑定 `{server, 工具, 参数模式}`，零跨域迁移——在一个场景积累的信任，一丝一毫不会泄漏到另一个场景。
- **技能（SKILL）是领域知识包**：红线规则、工具速查、错误恢复由开发者编写、随仓库版本化；画像学的是"用户是谁"，技能教的是"领域怎么运作"，两者正交互补，安全红线永远留在版本化的技能层而非可变的记忆里。
- **权限链**：只读操作自主执行；写操作进入审核——配置 Jev 时先由 Jev 判断与用户意图的一致性（通过自动执行），不通过或未配置则转人工在页面确认。Jev 同时按请求复杂度路由模型（复杂走 `qwen3-max`，简单走轻量模型）。
- **渐进式自治**：信任统计目前只观察不放行；自动化规则走"习惯发现 → 提议 → 用户批准 → 会话开始执行"，且执行不绕过现有权限链。介入点从"每次执行前"前移到"规则创建时"。

## 运行要求

- JDK 17

## 启动

密钥通过 `.env` 注入（`.env` 已被 `.gitignore` 排除）：

```bash
cp .env.example .env   # 然后编辑 .env，填入真实的 DASHSCOPE_API_KEY
chmod 600 .env
```

应用启动时会自动加载项目根目录的 `.env`（真实环境变量优先级更高），直接在 IntelliJ IDEA 中运行 `MioApplication` 即可（构建由 IDE 自带的 Maven 完成）。

启动后打开 <http://localhost:8080> 即可开始聊天；`/panel.html` 提供链路图与画像面板。

## 环境变量（写入 .env）

| 变量 | 说明 |
| --- | --- |
| `DASHSCOPE_API_KEY` | DashScope API key（必填，否则无法聊天）。 |
| `MCD_MCP_ENABLED` | 是否连接麦当劳 MCP（首个场景），默认 `true`。设为 `false` 可跳过连接，只做普通聊天。 |
| `MCD_MCP_TOKEN` | 麦当劳 MCP 令牌，在 [open.mcd.cn/mcp/doc](https://open.mcd.cn/mcp/doc) 控制台申请；填写有效 token 并把 `MCD_MCP_ENABLED` 设为 `true` 后才能点餐。 |
| `DASHSCOPE_BASE_URL` | 可选，切换 DashScope 地域地址。 |
| `DASHSCOPE_PROXY_HOST` / `DASHSCOPE_PROXY_PORT` | 可选，模型服务代理，默认端口 7890。 |
| `DASHSCOPE_FAST_MODEL` | 可选，轻量模型名（默认 `qwen-flash`）。配置 Jev 后由 Jev 按请求复杂度路由：复杂任务走 `qwen3-max`，简单请求走该模型。 |
| `TYPESAFE_API_KEY` | 可选，TypeSafe AI 的 Jev key。配置后写操作先由 Jev 审核，通过自动执行，不通过转人工；不配置则写操作一律转人工。 |
| `JEV_ENABLED` | 可选，默认 `true`，设为 `false` 关闭 Jev 审核。 |
| `JEV_AUTO_APPROVE_THRESHOLD` | 可选，Jev 自动放行的 Noul 概率阈值，默认 `0.8`。 |
| `MIO_SKILLS_DIR` | 可选，Agent 技能目录（文件系统路径），默认项目根目录下的 `./skills`。 |
| `MIO_DATA_DIR` | 可选，用户画像与记忆数据目录，默认项目根目录下的 `./.mio`（已被 `.gitignore` 排除）。 |
| `MIO_DISTILLER_ENABLED` | 可选，默认 `true`，设为 `false` 关闭对话中的画像自动提炼。 |
| `MIO_HABIT_DETECTOR_ENABLED` | 可选，默认 `true`，设为 `false` 关闭习惯检测（不再生成自动化提议）。 |
| `MIO_INJECT_FILTER_ENABLED` | 可选，默认 `true`，画像注入按当前消息相关性过滤（global 全量、领域条目按相关性）；设为 `false` 回退为全量注入。 |
| `MIO_INJECT_LLM_FILTER_ENABLED` | 可选，默认 `true`，规则未命中的领域页由轻量模型二次判定相关性（超时/失败倒向不注入）；设为 `false` 只走确定性规则。 |
| `MIO_INJECT_MAX_ENTRIES` | 可选，单次画像注入条目数上限，默认 `30`，超出时按置信度 × 新近度截断。 |
| `DASHSCOPE_EMBEDDING_MODEL` | 可选，向量化模型名（默认 `text-embedding-v4`），episode 情景回忆检索用。 |
| `MIO_RAG_ENABLED` | 可选，默认 `true`，设为 `false` 关闭情景回忆检索；embedding 不可用时自动静默降级，不影响对话。 |
| `MIO_RAG_TOP_K` / `MIO_RAG_MIN_SCORE` | 可选，单次召回条数上限（默认 `3`）与余弦相似度阈值（默认 `0.6`，低于阈值不注入）。 |

## 用户画像与记忆

对话中的显式偏好（口味/忌口、常用地址、预算、回复风格等）会由轻量模型自动提炼并维护为**画像 Wiki 页**（每页一个 Markdown 文件，YAML frontmatter 记录来源/置信度/更新时间，可整页持续改写），提炼时同步产出实体关系三元组（实体簿，为后续意图消歧打底）。注入时按当前消息做相关性过滤：`global` 作用域页面全量注入，领域（如 `mcd-ordering`）页面按相关性注入：先走确定性规则（命中领域别名或词面重叠），规则未命中的再由轻量模型批量二次判定（领域别名由技能包 `SKILL.md` frontmatter 的 `aliases` 字段声明；可用 `MIO_INJECT_FILTER_ENABLED=false` 回退为全量注入）；同时按当前消息向量召回相关的历史对话片段，作为「历史回忆」参考段注入（阈值过滤，宁缺毋滥）。数据全部落盘在本地数据目录（默认项目根目录下的 `./.mio`，已被 `.gitignore` 排除，不会入库）：

```
.mio/
├── memory/events-YYYY-MM.jsonl    ← 执行链路事件日志，按月滚动（每行带 eventId 幂等键）
├── memory/episodes.jsonl          ← 会话消息原始记录（画像可重建的源）
├── memory/episodes-vectors.jsonl  ← episode 向量索引（embedding，可随时重建）
├── wiki/index.md                  ← 画像 Wiki 目录（自动重建）
├── wiki/global/*.md               ← 全局画像页（跨场景恒真）
├── wiki/domains/<领域>/*.md       ← 领域画像页（按场景隔离）
├── entities.jsonl                 ← 实体簿：三元组 + 双时态字段（KG 的 Lv0 形态）
├── trust.json                     ← 信任规则统计
└── automations.json               ← 自动化规则（习惯提议 → 用户批准 → 生效）
```

旧版扁平画像 `profile.json` 在首次启动时自动迁移为 Wiki 页，原文件改名 `profile.json.migrated` 保留可查。

- 画像在面板（`/panel.html` 的「画像」视图）中可见、可删除，也可通过 API 管理：`GET/PUT/DELETE /api/profile`（按 slug 操作整页）；实体簿只读观察：`GET /api/entities`。
- 信任统计（`GET/DELETE /api/trust`）只记录人工对写操作的批准/拒绝次数，本轮**只观察、不做自动放行**（自动放行在后续阶段实现）。
- 提炼只提取用户明确表达的偏好；健康、政治、宗教等敏感主题不提取。
- 画像页文件损坏时改名 `.corrupted-<时间戳>` 保留并跳过（fail-open，不阻断对话）。

### 自动化规则（习惯发现 → 提议 → 批准 → 会话开始执行）

习惯检测器（统计式，不用 LLM）扫描事件日志：某白名单工具调用 ≥3 次且分布在 ≥2 个不同日期，即生成一条自动化提议（`PROPOSED`）。当前白名单工具来自首个场景：`auto-bind-coupons`、`draw-lottery`、`query-my-coupons`、`query-promotions`，其他工具永不提议。

- 提议在面板「画像」视图中可见，**必须由用户显式批准**（`POST /api/automations/{id}/approve`）才生效；拒绝（`REJECTED`）后不再重复提议；已批准规则可吊销（`DELETE /api/automations/{id}`）。
- 已批准（`ACTIVE`）规则会在会话开始时作为参考信息注入，Agent 感知后主动执行——**不绕过现有权限链**：只读查询直接执行，写操作仍过 Jev/人工审核。
- 批准/拒绝/吊销动作都会写入事件日志，保持审计链一致。
- 自动化规则文件损坏时按无规则处理（fail-closed），损坏文件改名 `.corrupted-<时间戳>` 保留。

## 技能目录（领域知识包）

Agent 技能放在项目根目录的 `skills/` 下（当前为 `skills/mcd-ordering/`），随仓库版本化，但通过文件系统加载——修改技能内容无需重新构建，重启应用即可生效。运行时代码只读使用该目录。启动时会强校验 `mcd-ordering` 技能存在，缺失则启动失败。

接入新业务场景的方向：新增一个技能目录携带该领域的红线、工具速查与画像 schema，记忆与权限内核保持不变。

## 演进路线

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| 1 | 画像注入相关性过滤；事件发布/异步任务接缝；事件幂等键 | ✅ 已完成 |
| 2 | 画像 Wiki 化：扁平 KV → 按作用域组织的主题页（参考 Karpathy LLM Wiki 模式），提炼器升级为整页改写，顺手产出实体三元组 | ✅ 已完成 |
| 3 | episodes 向量检索 Lv0（DashScope embedding + 本地内存余弦，服务情景回忆与意图消歧） | ✅ 已完成 |
| 4 | 意图识别消费记忆：域先验 + 消歧检索（实体簿接入消费方） | 未开始 |
| 5+ | 云端中间件（托管向量库 / Redis / MQ / 图库）：均留有接缝与量化触发条件，信号出现才接入，本地模式永远可用 | 按信号触发 |

## 试用示例（首个场景：麦当劳点餐）

- 「查询麦当劳常见餐品的营养信息，帮我搭配一份约 600 千卡的餐食。」
- 「获取我的可配送地址列表。」
- 「帮我在附近的门店点一份巨无霸套餐。」

只读查询由模型自主完成；下单等写操作会进入审核——配置了 Jev 时审核通过会自动执行（回复中会注明），否则在页面上弹出确认卡片，允许后才会真正执行。
