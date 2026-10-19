# Multi-Agent 运行时

目标方案：[Iris Multi Agent Design](https://q0kyes8lnt5.feishu.cn/wiki/SVz6w01hui1eKekTFqbcLHEinXg)。主 Agent 通过能力注册表委派任务，领域实例隔离上下文与工具，业务动作仍经确定性权限检查与任务级人工确认。

实现按模块推进：任务/能力契约与 PG 存储；主从调用与确认恢复；领域接入、入口开关及本地集成评测。高级后台事件调度、按事实依赖选择性重规划和生产资格评测另有验收要求，不能仅凭固定模型测试宣布完成。

`goals`、`tasks`、`agent_states` 的 DDL 见 [schema.sql](../db/schema.sql)。新增协作关键状态不回退内存；状态更新检查 revision 并与稳定身份的审计事件同事务提交。测试中的内存存储仅为 test double。SDK 会话 checkpoint 与业务操作记录有不同职责，恢复期间不能把一个已开始副作用的调用直接重跑。

RunBudget 由主从请求共用，限制前台模型/Jev、工具、子任务恢复次数、token、上下文大小和执行时间。未返回模型 usage 时显式标记不完整；并发调用及服务端计费存在边界，不能据此声称精确费用上限。跨多轮长目标的持久预算将在后台任务阶段补齐。
