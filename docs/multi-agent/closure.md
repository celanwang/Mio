# Multi-Agent v6 收尾与验收

本次目标是把现有多 Agent 能力收敛为可暂停、可恢复、可关闭、可核查、可验收的前台受控系统。验收覆盖确定性运行边界与离线设施，不把固定模型剧本当作真实模型的业务成功率。

## 当前完成边界

| 模块 | 完成标准 | 验证入口 |
| --- | --- | --- |
| 主从与权限 | 能力发现、工具隔离、逐次确认、真实用户授权依据 | SupervisorIntegrationTest、DomainRuntimeTest |
| 事实与局部修复 | 输入类型、版本、有效期、依赖失效与前台重新委派 | ShortLivedFactTest、DependencyInvalidationTest |
| 持久执行 | PG 租约、执行代次、SDK 检查点 CAS、旧执行拒绝写入 | ExecutionLeaseTest、JdbcPostgresIntegrationTest |
| 准备队列 | 原子入队/启动、显式领取、启动后不重放、完成确认补记 | JdbcTaskQueueIntegrationTest |
| 目标生命周期 | 暂停中断执行并作废旧批准；恢复不重跑；关闭保留历史及未知结果 | GoalLifecycleTest、JdbcGoalLifecycleIntegrationTest |
| 预算与核查 | 累计预算可见、缺失用量不归零、停止后可独立核查原操作 | PersistentBudgetTest、JdbcGoalLifecycleIntegrationTest |
| 收尾验收 | 同次运行编号、源码指纹、实库覆盖、固定剧本不能晋升 | eval.acceptance、test_acceptance.py |

目标控制版本 controlRevision 与用户意图 revision 分离。暂停和恢复不追加伪造用户表达，不补发旧批准，也不为已有目标重新开户或增加预算。关闭是逻辑归档，不删除会话、任务、回执、检查点或审计事件，不声称外部订单已经取消或履约完成。未核实的凭据范围占用继续保留；只有可信业务证据能解除它。

## 运行与操作

受控预览仍需显式设置 MIO_MULTI_AGENT_ENABLED=true，默认关闭。首次启动会补齐 goals.control_revision；旧 JSON 默认 ACTIVE、控制版本零。升级前停止旧进程，按同一版本使用数据库；租约不能保护仍使用旧覆盖写法的混合版本部署。

`GET /api/tasks?sessionId=...` 返回任务、目标概览和累计预算。界面显示进行中、排队、补问、待确认、待核查等状态，以及模型、工具、任务预占和已报告 token。展开预算说明后可查看 PENDING、UNKNOWN 与历史不可追溯，不等同于零费用；token 阈值仍是停止阈值，不是供应商账单硬上限。

`POST /api/tasks/goal` 接受 sessionId、expectedControlRevision 和 ACTIVE/PAUSED/CLOSED。控制必须由用户显式发出，未提供给模型。请求按租约、目标、任务、队列的顺序加锁；版本冲突返回 409，数据库或审计失败回滚全部变更。暂停或关闭可撤销当前执行代次；已发出或已进入提交阶段的外部请求可能继续返回，因此其真实回执仍可追加，结果未知时不会自动重放。

暂停保留未开始的队列任务、补问和已接收回答，恢复后需要显式继续。已经运行而被中断的任务与旧待批准任务不会恢复；事实过期及技能变更仍按原规则失效。关闭后不再接受对该目标的新对话、领取、回答恢复或批准；新目标通过新对话创建，原未知操作的跨会话保护不被清除。

`POST /api/tasks/{taskId}/operations/{operationId}/reconcile` 接受 sessionId，在暂停或关闭后仍可读取受支持操作的关联回执。此路径不调用主模型、不创建新订单，仍消耗原目标工具读取预算，不绕过额度。界面仅为服务端声明可核查的未知操作提供入口。读取预算耗尽或关联证据缺失时保留未知，需到供应商端人工核对，不能在系统中伪造未执行证明。

## 一次性完整验收

运行 `./scripts/eval-postgres.sh`。脚本使用隔离 PostgreSQL 容器，模型与供应商均为 test double，不需要模型密钥，不联系真实业务。默认单独运行 `./scripts/eval.sh` 时允许缺少 PG，但报告会标记 POSTGRES_REQUIRED，不宣称完整验收。

输出位于 target/evaluation/acceptance.json 与 acceptance.md，原有设施指标继续位于 summary.md 及同目录 JSON 文件。每次开始即覆盖旧成功状态为 RUNNING；Java XML、Python 结果与最终报告必须属于同一运行编号。源码指纹包含 src、scripts、eval、skills 和 pom.xml，拒绝用变更前的报告解释变更后的代码。完整结论要求生命周期、队列、持久化、记忆一致性及完整前台编排五类 PG 套件真实执行且零跳过；报告始终将生产资格标为 NOT_ESTABLISHED。后续补齐的证据边界见 [任务输出契约](result-contracts.md) 与 [完整编排验收](orchestration-acceptance.md)。

页面的隔离验收覆盖忙碌期间暂停、恢复不自动执行、关闭保留草稿与待核查操作、关闭后核查，以及响应丢失后回读。客户端按控制版本拒绝过期状态，并保留较新的预算版本。测试容器、临时 HTTP 服务和验收页面结束后清理，不增加 .github 流程。

## 扩展阶段

以下内容有独立前提，不作为本次工程收尾的隐含完成项。

| 扩展 | 开始实现前需要的条件 |
| --- | --- |
| 后台自主消费、独立任务续租 | 用户可表达持续执行范围、任务截止时间、账户级预算与通知策略；明确读取成本和中断语义 |
| 字段级意图修订 | 版本化目标字段与授权影响规则，不能仅凭模型判断保留旧批准 |
| 多供应商业务闭环 | 各供应商的可信回执适配器、稳定资源身份及可核验的未提交/幂等语义 |
| 跨主机接管 | 供应商草稿、连接、凭据和本地关联回执可迁移，完成混合故障与分区验收 |
| 账户预算与数据保留 | 预算归属、背景记忆模型统计、人工额度管理及明确的留存/清理规则 |
| 真实模型资格与逐步上线 | 在 mock 业务世界采样真实模型，与单 Agent 对照，按领域、风险和模型路由审查完成率、成本及人工评审 |

这些扩展不能靠打开预览开关或提高固定剧本通过率代替。当前闭环提供继续扩展的状态、证据与控制边界。
