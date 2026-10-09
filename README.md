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

## 试用示例

- 「查询麦当劳常见餐品的营养信息，帮我搭配一份约 600 千卡的餐食。」
- 「获取我的可配送地址列表。」
- 「帮我在附近的门店点一份巨无霸套餐。」

只读查询由模型自主完成；下单等写操作会进入审核——配置了 Jev 时审核通过会自动执行（回复中会注明），否则在页面上弹出确认卡片，允许后才会真正执行。
