# Mio

个人 AI 助手 MVP：网页聊天 + 麦当劳点餐。基于 Java 17 + Spring Boot + AgentScope Java（DashScope 模型），麦当劳能力通过 MCP 接入。模型自主识别意图、规划步骤并执行只读查询（菜单、价格、门店、订单等）；写操作（下单、取消、领券、积分兑换等）进入审核：配置 Jev 时先由 Jev 判断与用户意图的一致性，通过则自动执行，不通过或未配置 Jev 时转人工在页面上确认。

## 运行要求

- JDK 17

## 启动

密钥通过 `.env` 注入（`.env` 已被 `.gitignore` 排除）：

```bash
cp .env.example .env   # 然后编辑 .env，填入真实的 DASHSCOPE_API_KEY
chmod 600 .env
```

应用启动时会自动加载项目根目录的 `.env`（真实环境变量优先级更高），直接在 IntelliJ IDEA 中运行 `MioApplication` 即可（构建由 IDE 自带的 Maven 完成）。

启动后打开 <http://localhost:8080> 即可开始聊天。

## 环境变量（写入 .env）

| 变量 | 说明 |
| --- | --- |
| `DASHSCOPE_API_KEY` | DashScope API key（必填，否则无法聊天）。 |
| `MCD_MCP_ENABLED` | 是否连接麦当劳 MCP，默认 `true`。设为 `false` 可跳过麦当劳连接，只做普通聊天。 |
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

## 用户画像与记忆

对话中的显式偏好（口味/忌口、常用地址、预算、回复风格等）会由轻量模型自动提炼为用户画像，并在后续对话中作为参考信息注入（标注为非指令，优先级低于用户当次明确表达）。数据全部落盘在本地数据目录（默认项目根目录下的 `./.mio`，已被 `.gitignore` 排除，不会入库）：

```
.mio/
├── memory/events-YYYY-MM.jsonl   ← 执行链路事件日志，按月滚动
├── memory/episodes.jsonl         ← 会话消息原始记录（画像可重建的源）
├── profile.json                  ← 用户画像（物化视图）
└── trust.json                    ← 信任规则统计
```

- 画像在面板（`/panel.html` 的「画像」视图）中可见、可删除，也可通过 API 管理：`GET/PUT/DELETE /api/profile`。
- 信任统计（`GET/DELETE /api/trust`）只记录人工对写操作的批准/拒绝次数，本轮**只观察、不做自动放行**（自动放行在后续阶段实现）。
- 提炼只提取用户明确表达的偏好；健康、政治、宗教等敏感主题不提取。
- 画像文件损坏时按空画像处理（fail-open，不阻断对话），损坏文件会改名 `.corrupted-<时间戳>` 保留。

## 技能目录

Agent 技能（如 `skills/mcd-ordering/`）放在项目根目录的 `skills/` 下，随仓库版本化，但通过文件系统加载——修改技能内容无需重新构建，重启应用即可生效。运行时代码只读使用该目录。启动时会强校验 `mcd-ordering` 技能存在，缺失则启动失败。

## 试用示例

- 「查询麦当劳常见餐品的营养信息，帮我搭配一份约 600 千卡的餐食。」
- 「获取我的可配送地址列表。」
- 「帮我在附近的门店点一份巨无霸套餐。」

只读查询由模型自主完成；下单等写操作会进入审核——配置了 Jev 时审核通过会自动执行（回复中会注明），否则在页面上弹出确认卡片，允许后才会真正执行。
