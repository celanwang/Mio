# Mio

一个 **Personal Agent** 的探索与实践：不止于"会聊天"，而是能记住你、理解你的习惯、在你授权的边界内主动为你做事的个人智能体。

基于 Java 17 + Spring Boot + AgentScope Java（DashScope 模型）。麦当劳点餐是第一个落地场景（通过 MCP 接入），用于验证记忆、权限、审核这条主链路；架构按多场景泛化设计，接入新业务 = 新增一个技能包，内核不变。目前已接入的渠道：麦当劳点餐（MCP）、微博（官方 MCP，OAuth 按需授权）、腾讯出行打车（本地 Python 桥接官方接口）。

## 设计原则

1. **事件流是唯一真相源**：对话、工具调用、审核判定、人工批准全部进 append-only 事件日志；画像、信任、自动化规则都是事件流上可重建的蒸馏视图，系统永远可以重算。
2. **每层有明确的失败方向**：画像损坏 fail-open（降级为不贴心，不阻断对话）；信任与自动化规则损坏 fail-closed（倒向更多人工确认，永不倒向更多放行）。
3. **学习产生候选，授权来自用户**：系统可以提炼偏好、统计习惯、提出自动化建议，但任何放行权都由用户显式授予；记忆注入一律标注为参考信息而非指令。
4. **PG 是唯一真相源，接缝留给未来**：全部记忆数据（事件/消息/向量/实体/画像/信任/自动化）落 PostgreSQL + pgvector，本地不落任何记忆数据文件（凭证类文件除外：`.env`、`.weibo/`、`.tencent-ride/`）；在事件发布、异步任务、存储后端等关键位置预留接口，MinIO/OSS 对象存储、MQ、云服务按真实触发信号逐个接入，不提前建设。

## 架构总览

```
蒸馏层    用户画像（可重建的物化视图）   信任规则（策略）      自动化规则（授权）
             ↑ 提炼                        ↑ 统计晋升            ↑ 习惯检测
记忆层    长期：PostgreSQL + pgvector（events / messages / embeddings / entities / profiles / trusts / automations，唯一真相源）
             ↑ 巩固
          短期：会话内上下文（内存）
事件源    对话消息 / 工具调用 / Jev 判定 / 人工批准·拒绝
```

- **画像三层作用域**：`global`（跨场景恒真，全量注入）/ `domains/{domain}`（领域隔离，按当前消息相关性注入）/ 会话级例外。晋升单向，信任规则绑定 `{server, 工具, 参数模式}`，零跨域迁移——在一个场景积累的信任，一丝一毫不会泄漏到另一个场景。
- **技能（SKILL）是领域知识包**：红线规则、工具速查、错误恢复由开发者编写、随仓库版本化；画像学的是"用户是谁"，技能教的是"领域怎么运作"，两者正交互补，安全红线永远留在版本化的技能层而非可变的记忆里。
- **权限链（`security` 包，唯一裁决点 `PolicyEngine`）**：规则只按工具名匹配，三态 allow/ask/deny，求值固定 deny > ask > allow，无规则覆盖一律转人工。只读操作自主执行；写操作按可逆性分三轨——不可逆操作（下单/支付类）走硬人工轨永远人工确认，可逆写操作走自动审核轨（配置 Jev 时先判断与用户意图的一致性，通过自动执行，不通过或未配置转人工），首次出现的新工具一律人工并在确认卡片标记「首见」提示核对描述。每次裁决写 `policy_decision` 事件留痕。配套机制：工具返回统一以 `<tool_data>` 分隔符包裹（数据而非指令）；工具注册守卫防多来源同名遮蔽（冲突即失败）；ReAct 循环显式迭代上限；急停开关（`POST /api/security/kill-switch`）开启后非只读一律转人工并吊销全部 ACTIVE 自动化；`/chat` 与 `/api/**` 仅允许本机回环访问。裁决器支持 `shadow`（只留痕）/`enforce` 两档模式，当前默认 shadow。Jev 同时按请求复杂度三档路由模型（长任务/高要求走主模型，点餐、打车等工具流程走标准模型 `qwen3.7-plus`，简单请求走轻量模型）。
- **渐进式自治**：信任统计目前只观察不放行；自动化规则走"习惯发现 → 提议 → 用户批准 → 会话开始执行"，且执行不绕过现有权限链。介入点从"每次执行前"前移到"规则创建时"。

## 运行要求

- JDK 17

## 数据库与部署

- **表结构定稿**:[docs/db/schema.sql](docs/db/schema.sql)——7 张表 `events` / `messages` / `embeddings` / `entities` / `profiles` / `trusts` / `automations`，设计规则（表名单词复数、主键统一 `id`、五种字段类型、派生态与真相源隔离、变更纪律）见文件头注释。代码侧 PG 实现已对齐定稿表结构（启动时幂等自建全部 7 张）。
- **基础设施**:[docs/deploy/docker-compose.yml](docs/deploy/docker-compose.yml)——PG + pgvector（长期记忆/唯一真相源）+ Redis（短期记忆，AOF everysec，`maxmemory 256mb` + `allkeys-lru`,key 全部可弃）。密码经该目录 `.env` 注入（`PG_PASSWORD` / `REDIS_PASSWORD`，勿提交）；安全组 5432/6379 必须配置可信 IP 白名单，禁止 `0.0.0.0/0`；应用与数据库同机部署时改绑 `127.0.0.1` 并关闭安全组端口。
- **故障方向**:Redis 不可达回退进程内存（fail-open，重启丢会话）;PG 不可达时画像/回忆静默降级（fail-open)，信任/自动化倒向全部人工确认（fail-closed)。PG 是唯一真相源，`pg_dump` 定时备份挂在演进路线。

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
| `WEIBO_MCP_ENABLED` | 是否启用微博官方 MCP，默认 `true`。微博无静态 token：首次微博任务时页面提供「授权微博」入口，OAuth 完成后凭证保存在本机 `.weibo/auth.json`（已 gitignore）；对话与管理接口统一仅允许本机回环访问。 |
| `TENCENT_RIDE_ENABLED` / `TENCENT_RIDE_TOKEN` | 腾讯出行打车开关（默认 `true`）与令牌；token 获取方式见 `skills/tencent-ride` 技能的 quick-start 说明，只经 stdin 传给本地 Python 子进程，不入日志与工具 schema。 |
| `TENCENT_RIDE_PYTHON` / `TENCENT_RIDE_CITY` | 可选，Python 解释器路径（默认 `python`）与常住城市（留空则打车时主动询问本次城市）。 |
| `DASHSCOPE_BASE_URL` | 可选，切换 DashScope 地域地址。 |
| `DASHSCOPE_PROXY_HOST` / `DASHSCOPE_PROXY_PORT` | 可选，模型服务代理，默认端口 7890。 |
| `DASHSCOPE_MODEL` | 可选，主模型名（默认 `qwen3.8-max`）。配置 Jev 后由 Jev 按请求复杂度路由：长任务/高要求内容走该模型。账号无该模型权限时 DashScope 返回 404，可改为 `qwen-plus`（`qwen-max` 输入上限 30k，微博工具全量注册后会超限，不建议）。 |
| `DASHSCOPE_PLUS_MODEL` | 可选，标准模型名（默认 `qwen3.7-plus`，qwen3.8 系列无 plus 档）。Jev 路由的中间档：点餐、打车、微博等工具流程任务走该模型。 |
| `DASHSCOPE_FAST_MODEL` | 可选，轻量模型名（默认 `qwen3.8-flash`）。Jev 路由的简单档：闲聊与一步查询走该模型。 |
| `TYPESAFE_API_KEY` | 可选，TypeSafe AI 的 Jev key。配置后写操作先由 Jev 审核，通过自动执行，不通过转人工；不配置则写操作一律转人工。 |
| `JEV_ENABLED` | 可选，默认 `true`，设为 `false` 关闭 Jev 审核。 |
| `JEV_AUTO_APPROVE_THRESHOLD` | 可选，Jev 自动放行的 Noul 概率阈值，默认 `0.8`。 |
| `MIO_SKILLS_DIR` | 可选，Agent 技能目录（文件系统路径），默认项目根目录下的 `./skills`。 |
| `MIO_SECURITY_LOCAL_ONLY` | 可选，默认 `true`，`/chat` 与 `/api/**` 仅允许本机回环访问；仅在部署形态变化时考虑关闭并另行加固。 |
| `MIO_SECURITY_POLICY_MODE` | 可选，权限裁决模式：`shadow`（默认，裁决只留痕不改变行为）/ `enforce`（硬编码 DENY 规则真正生效）。 |
| `MIO_AGENT_MAX_ITERS` | 可选，ReAct 循环迭代上限，默认 `20`。 |
| `MIO_DISTILLER_ENABLED` | 可选，默认 `true`，设为 `false` 关闭对话中的画像自动提炼。 |
| `MIO_HABIT_DETECTOR_ENABLED` | 可选，默认 `true`，设为 `false` 关闭习惯检测（不再生成自动化提议）。 |
| `MIO_INJECT_FILTER_ENABLED` | 可选，默认 `true`，画像注入按当前消息相关性过滤（global 全量、领域条目按相关性）；设为 `false` 回退为全量注入。 |
| `MIO_INJECT_LLM_FILTER_ENABLED` | 可选，默认 `true`，规则未命中的领域页由轻量模型二次判定相关性（超时/失败倒向不注入）；设为 `false` 只走确定性规则。 |
| `MIO_INJECT_VECTOR_ENABLED` / `MIO_INJECT_VECTOR_MIN_SCORE` | 可选，画像页向量召回开关（默认 `true`）与余弦阈值（默认 `0.35`，已用真实 embedding 数据校准：相关对 0.40–0.58、无关对 ≤0.30）；embedding 不可用时静默降级。 |
| `MIO_INJECT_MAX_ENTRIES` | 可选，单次画像注入条目数上限，默认 `30`，超出时按置信度 × 新近度截断。 |
| `DASHSCOPE_EMBEDDING_MODEL` | 可选，向量化模型名（默认 `text-embedding-v4`），episode 情景回忆检索用。 |
| `MIO_RAG_ENABLED` | 可选，默认 `true`，设为 `false` 关闭情景回忆检索；embedding 不可用时自动静默降级，不影响对话。 |
| `MIO_RAG_TOP_K` / `MIO_RAG_MIN_SCORE` | 可选，单次召回条数上限（默认 `3`）与余弦相似度阈值（默认 `0.6`，低于阈值不注入）。 |
| `MIO_PG_JDBC_URL` / `MIO_PG_USERNAME` / `MIO_PG_PASSWORD` | 必填，PostgreSQL 连接信息（记忆唯一后端，库需启用 vector 扩展；表结构启动时幂等自建）。 |
| `MIO_REDIS_HOST` / `MIO_REDIS_PORT` / `MIO_REDIS_PASSWORD` | 可选，短期记忆 Redis 连接信息；**预留配置，代码侧尚未接入**，接入后留空则回退进程内存（fail-open）。 |

## 用户画像与记忆

对话中的显式偏好（口味/忌口、常用地址、预算、回复风格等）会由轻量模型自动提炼并维护为**画像页**（profiles 表每页一行，content 为 Markdown 正文，可整页持续改写），提炼时同步产出实体关系三元组（实体簿，为后续意图消歧打底）。注入时按当前消息做相关性过滤：`global` 作用域页面全量注入，领域（如 `mcd-ordering`）页面按相关性注入：先走确定性规则（命中领域别名或词面重叠），规则未命中的再由轻量模型批量二次判定（领域别名由技能包 `SKILL.md` frontmatter 的 `aliases` 字段声明；可用 `MIO_INJECT_FILTER_ENABLED=false` 回退为全量注入）；同时按当前消息向量召回相关的历史对话片段，作为「历史回忆」参考段注入（阈值过滤，宁缺毋滥）。数据全部落 PostgreSQL + pgvector（唯一真相源，本地不落任何记忆数据文件）：

```
events       ← 执行链路事件日志（append-only，id 幂等键）
messages     ← 会话消息原始记录（画像可重建的源，带 session_id）
embeddings   ← 消息向量索引（与 messages 同 id，可随时 TRUNCATE 重建）
entities     ← 实体簿：三元组 + 双时态字段（KG 的 Lv0 形态）
profiles     ← 画像页：content 即 Markdown 逻辑文档（Memory as File 的 PG 表达）
trusts       ← 信任规则统计（tool_name + param_signature 复合主键，UPSERT 累加）
automations  ← 自动化规则（习惯提议 → 用户批准 → 生效）
```

**记忆存储：PostgreSQL + pgvector（唯一后端）**：配置 `MIO_PG_JDBC_URL` / `MIO_PG_USERNAME` / `MIO_PG_PASSWORD` 连接；消息原文与向量同库（向量引用不会悬空），向量检索走 pgvector 余弦距离（`<=>`），embedding 模型 text-embedding-v4、向量列固定 `vector(1024)`，表结构启动时 `CREATE TABLE IF NOT EXISTS` 幂等自建（定稿 DDL 见 [docs/db/schema.sql](docs/db/schema.sql)）。PG 不可达时画像/回忆 fail-open（warn + 静默降级，不阻断对话），信任/自动化 fail-closed（按无数据处理，倒向全部人工确认）。

- 画像在面板（`/panel.html` 的「画像」视图）中可见、可删除，也可通过 API 管理：`GET/PUT/DELETE /api/profile`（按 id 操作整页）；实体簿只读观察：`GET /api/entities`。
- 信任统计（`GET/DELETE /api/trust`）只记录人工对写操作的批准/拒绝次数，本轮**只观察、不做自动放行**（自动放行在后续阶段实现）。
- 提炼只提取用户明确表达的偏好；健康、政治、宗教等敏感主题不提取。
- 画像读写失败时按空画像处理（fail-open，不阻断对话）。

### 自动化规则（习惯发现 → 提议 → 批准 → 会话开始执行）

习惯检测器（统计式，不用 LLM）扫描事件日志：某白名单工具调用 ≥3 次且分布在 ≥2 个不同日期，即生成一条自动化提议（`PROPOSED`）。当前白名单工具来自首个场景：`auto-bind-coupons`、`draw-lottery`、`query-my-coupons`、`query-promotions`，其他工具永不提议。

- 提议在面板「画像」视图中可见，**必须由用户显式批准**（`POST /api/automations/{id}/approve`）才生效；拒绝（`REJECTED`）后不再重复提议；已批准规则可吊销（`DELETE /api/automations/{id}`）。
- 已批准（`ACTIVE`）规则会在会话开始时作为参考信息注入，Agent 感知后主动执行——**不绕过现有权限链**：只读查询直接执行，写操作仍过 Jev/人工审核。
- 批准/拒绝/吊销动作都会写入事件日志，保持审计链一致。
- 自动化规则存储故障时按无规则处理（fail-closed，倒向全部人工确认）。

## 技能目录（领域知识包）

Agent 技能放在项目根目录的 `skills/` 下（当前为 `skills/mcd-ordering/`、`skills/weibo-assistant/`、`skills/tencent-ride/`），随仓库版本化，但通过文件系统加载——修改技能内容无需重新构建，重启应用即可生效。运行时代码只读使用该目录。启动时会强校验这三个技能存在，缺失则启动失败。其中 `skills/tencent-ride/scripts/` 下的 Python 脚本由打车工具在运行时复制到数据目录执行。

接入新业务场景的方向：新增一个技能目录携带该领域的红线、工具速查与画像 schema，记忆与权限内核保持不变。

## 演进路线

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| 1 | 画像注入相关性过滤；事件发布/异步任务接缝；事件幂等键 | ✅ 已完成 |
| 2 | 画像 Wiki 化：扁平 KV → 按作用域组织的主题页（参考 Karpathy LLM Wiki 模式），提炼器升级为整页改写，顺手产出实体三元组 | ✅ 已完成 |
| 3 | episodes 向量检索 Lv0（DashScope embedding + PostgreSQL/pgvector 余弦检索，服务情景回忆与意图消歧） | ✅ 已完成 |
| 4 | 意图识别消费记忆：域先验 + 消歧检索（实体簿接入消费方） | 未开始 |
| 5+ | 云端中间件：PostgreSQL + pgvector 记忆存储已落地（唯一后端，本地不落记忆文件）；Redis 已部署待接入（短期记忆）；托管向量库 / MQ / 图库留有接缝与量化触发条件，信号出现才接入 | 部分完成 |

## 试用示例（首个场景：麦当劳点餐）

- 「查询麦当劳常见餐品的营养信息，帮我搭配一份约 600 千卡的餐食。」
- 「获取我的可配送地址列表。」
- 「帮我在附近的门店点一份巨无霸套餐。」

微博与打车渠道（需先完成微博 OAuth 授权 / 配置 `TENCENT_RIDE_TOKEN`）：

- 「读取我最近发布的微博，最多读取 5 条，整理一份带原文链接的摘要。不要评论或点赞。」
- 「我要打车。」（助手会主动询问本次城市与上下车地点，询价后经确认才下单）

只读查询由模型自主完成；写操作按可逆性分轨：下单/支付类不可逆操作一律在页面弹出确认卡片（首次出现的新工具会额外标记「首见」提示），可逆写操作配置了 Jev 时审核通过会自动执行（回复中会注明），否则同样在页面确认，允许后才会真正执行。


## 评测体系

运行 `./scripts/eval.sh` 执行完全离线的权限/记忆/习惯测试、三场景 mock 任务、注入四因子对照和指标自检。无需 `.env`、PG、模型 key 或真实业务工具；报告见 `target/evaluation/summary.md`，原始观察与诊断保存在本地 `target/evaluation/`。

[评测实现与运行说明](docs/evaluation/README.md) 对齐飞书《Iris - Evaluation Design》revision 26，涵盖黄金种子集、pass³、阈值校准、影子/回归、显式模型采样和晋升评审。固定剧本基线不等于模型资格；缺模型/人工标签/线上证据时门禁保持未就绪，不自动切换 enforce。

## Multi-Agent 受控预览

设置 `MIO_MULTI_AGENT_ENABLED=true` 后，主助手通过能力注册表向独立领域 Agent 委派任务；新增领域使用 `DomainDefinition`，任务运行时没有固定品牌流程。默认关闭。当前接通主从调用、领域工具和技能隔离、补问恢复、任务级审批、PG 检查点与提交前记录，页面可分别处理各项任务。能力发现同时返回本地可用性，已知不可用时阻止委派，准备阶段只开放准备工具；发现不查询真实业务或探测体验额度。没有结构化供应商回执的写操作保持待核查，不自动重试。

跨任务交接支持服务端声明的必填事实契约与短时报价快照，按来源、类型、版本和有效期校验；跨领域报价仅供查询分析，下单仍沿用原子会话的核价与确认。

目标区域支持用户确认验收范围，并对可信事实设置耗时、距离等数值限制或资源编号条件。系统按当前证据判断确认范围是否满足；意图修改需重新确认，未知操作继续阻塞。确认验收条件不会批准业务写入，也不代表未覆盖的需求已完成。操作、状态与接口见[目标级证据验收](docs/multi-agent/goal-acceptance.md)。完整离线实库验收运行 `./scripts/eval-postgres.sh`，需 Docker、Java 17、Maven、Python 3 和 Node.js 18+。

已声明依赖失效时，运行时标记受影响任务并保留无关审批；主助手通过计划复核在当前目标版本内重新委派准备任务。历史执行状态、结果有效性和业务回执分别保留。

前台请求通过 PG 会话租约互斥，任务记录所属执行代次，主从 SDK 检查点使用版本 CAS 与租约保护；过期请求不能覆盖新执行状态。跨主机业务接管尚待验证。

准备任务可设置 `dispatch=QUEUED` 持久入队，再由主助手或任务卡片显式领取。启动前失联可重新领取，启动后中断不会重放；当前按前台请求消费，尚无后台自动执行。

[运行与验证说明](docs/multi-agent/README.md) 列出入口、离线回归、真实 PostgreSQL 测试及阶段边界。目标级预算现已跨轮持久累计，模型预占和用量结算保留缺失与未结算状态；已确认准备计划的事件调度与字段局部修订见下文持续目标模块；后台业务写入、跨主机接管和真实模型资格评测仍待后续阶段。

多 Agent 目标支持暂停、恢复与关闭，页面展示累计预算和待核查事项；停止后仍可核查受支持的原操作。统一离线收尾验收运行 `./scripts/eval-postgres.sh`，结果见 `target/evaluation/acceptance.md`。[收尾范围与扩展条件](docs/multi-agent/closure.md) 明确区分工程验收与生产资格。

前台模型取消或异常时保留已观察消耗并结算未知；进程中断留下的用量预占由固定批次恢复器按数据库截止时间转为未知，可信晚到完整回报只补差额。调用额度不退回，查询接口保持只读。升级与诊断见[前台模型用量收尾](docs/foreground-usage.md)。

## 记忆后台任务

原文与画像提炼、情景向量任务同事务登记，后台支持有限重试、检查点恢复和执行代次保护。历史召回按命中 id 回源 PG，补建按分页游标推进。后台调用按 UTC 日预占调用次数和输入字符，缺失用量不视作零费用；画像面板提供只读积压和用量诊断。默认启用记忆后台消费，可用 `MIO_MEMORY_JOBS_ENABLED=false` 暂停而保留任务。配置、升级和恢复步骤见[记忆后台任务说明](docs/memory-background-jobs.md)。

## 发布与维护

构建版本为 6.0.0。独立维护入口 `./scripts/admin.sh` 默认只读预览，支持单条 DEAD 记忆作业重试及备份恢复后的执行身份隔离；执行需停机声明、预览摘要和审计原因。完整验收包含真实 dump/restore，并将 JAR 哈希、构建来源与同次报告绑定。操作步骤和恢复边界见[发布与维护闭环](docs/release-maintenance.md)。

## 持续目标执行

对话目标区域的“持续目标”连接结构化计划、后台准备、局部修订、并行步骤、业务资源、方案比较及文档工作区。主助手可提出建议，用户确认字段、能力、期限与额度后后台推进；实际业务写入继续由原审批链处理。新增信息研究与文档助手，支持可配置能力包及只读远程准备服务。功能、操作入口和明确边界见[持续目标执行](docs/continuous-goals.md)。
