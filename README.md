# Mio

个人 AI 助手 MVP：网页聊天 + 麦当劳点餐。基于 Java 17 + Spring Boot + AgentScope Java（DashScope 模型），麦当劳能力通过 MCP 接入，内置 `mcd-ordering` 技能指导点餐流程；涉及外部服务的工具操作会在页面上先征求你的确认。

## 运行要求

- JDK 17

## 启动

密钥通过 `.env` 注入（与 agentscope-jev-qwen-demo 相同的方式，`.env` 已被 `.gitignore` 排除）：

```bash
cp .env.example .env   # 然后编辑 .env，填入真实的 DASHSCOPE_API_KEY
chmod 600 .env
```

应用启动时会自动加载项目根目录的 `.env`（真实环境变量优先级更高），直接在 IntelliJ IDEA 中运行 `MioApplication` 即可。命令行方式：`./mvnw spring-boot:run`。

启动后打开 <http://localhost:8080> 即可开始聊天。

## 环境变量（写入 .env）

| 变量 | 说明 |
| --- | --- |
| `DASHSCOPE_API_KEY` | DashScope API key（必填，否则无法聊天）。 |
| `MCD_MCP_ENABLED` | 是否连接麦当劳 MCP，默认 `true`。设为 `false` 可跳过麦当劳连接，只做普通聊天。 |
| `MCD_MCP_TOKEN` | 麦当劳 MCP 令牌，在 [open.mcd.cn/mcp/doc](https://open.mcd.cn/mcp/doc) 控制台申请；填写有效 token 并把 `MCD_MCP_ENABLED` 设为 `true` 后才能点餐。 |
| `DASHSCOPE_BASE_URL` | 可选，切换 DashScope 地域地址。 |
| `DASHSCOPE_PROXY_HOST` / `DASHSCOPE_PROXY_PORT` | 可选，模型服务代理，默认端口 7890。 |

## 试用示例

- 「查询麦当劳常见餐品的营养信息，帮我搭配一份约 600 千卡的餐食。」
- 「获取我的可配送地址列表。」
- 「帮我在附近的门店点一份巨无霸套餐。」

下单等写操作会先在页面上弹出确认卡片，允许后才会真正执行。
