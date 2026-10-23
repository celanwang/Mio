# 发布与维护闭环

本模块收敛失败作业处理、备份恢复隔离和构建验收来源。维护通过独立 Java 入口运行，不启动 Spring、Agent、后台消费者或业务客户端。页面诊断继续保持只读。当前构建版本为 6.0.0；原有 v6.0 标签属于原始代码基线，不移动已有标签来覆盖后续实现。

## 维护入口

运行 `./scripts/admin.sh --help` 查看用法。入口要求显式设置 MIO_ADMIN_PG_URL、MIO_ADMIN_PG_USERNAME、MIO_ADMIN_PG_PASSWORD，不读取应用 .env，不回退 PG_URL 等生产配置，不创建或迁移表。密码通过环境传入，不放在命令参数。目标库必须已经运行过当前版本的初始化；缺表或状态损坏时直接失败。

所有命令默认只读，输出操作类型、目标、受影响数量和 SHA-256 摘要，不输出原文、画像、供应商响应或结果检查点。预览在只读可重复读事务内形成。执行前停止所有前台和后台实例；--offline 是操作人员对停机状态的显式声明，工具无法代替进程管理器证明所有远端实例已停止。

执行需要同时提供 --apply、--expected、--reason、--offline。执行事务对运行状态表加排他锁，最多等待五秒，重新计算摘要并比较；并发更新、数据库/模式切换或陈旧预览均须重新预览。整体事务上限三十秒。状态变更和 maintenance_applied 审计同事务提交；任一步失败全部回滚。原因长度为 1–500 字，写入审计，勿包含密码或私人原文。维护后应用仍需人工启动；不要在旧实例运行期间执行维护。

## 重试明确失败的记忆作业

先在画像面板诊断失败原因，修复输入限制、供应商配置或数据库问题，再停止所有应用实例。该命令只接受单条 DEAD 作业，不支持批量重置、修改模型结果或抹除账目。

```bash
./scripts/admin.sh retry-memory-job --job 'DISTILL:消息编号'
./scripts/admin.sh retry-memory-job --job 'DISTILL:消息编号' \
  --apply --expected '预览返回的64位摘要' --reason '修复失败原因后重试' --offline
```

执行将作业转为 RETRY，处理尝试次数归零，generation 增加，清除旧领取身份与等待时间。原始消息、作业顺序、保存的检查点、画像版本与日额度保持原值。存在检查点时仍走已有版本/来源校验，不因人工重试绕过 schemaVersion、embedding 模型、人工画像或删除标记保护；不兼容检查点需要先修复兼容问题，工具不提供清除结果的捷径。没有检查点的重试可能产生新的供应商调用，仍受当日调用和输入额度约束。重复操作需再次预览并留下新的审计。

## 从备份恢复运行状态

备份恢复是数据库快照回退，供应商世界不会随之回退。恢复前已发生但备份中尚未记录的订单、发帖和计费无法由本地数据库推断。恢复后必须先隔离旧进程、阻断业务写入，核对恢复点之后的外部操作，再决定是否恢复服务。这个工具撤销快照内的执行身份，保留未知证据；它不承诺跨备份的 exactly-once，也不自动发起查单、撤销或重试业务操作。

使用受信备份恢复到新建的隔离目标库，检查 PostgreSQL/pgvector 版本、角色与权限，再核对数据。整库 custom-format 备份使用 pg_dump -Fc，恢复使用 pg_restore --exit-on-error；只按 schema 导出的测试备份不自动包含 public 中的 vector 扩展，因此验收目标先创建该扩展。生产备份还需独立管理角色等集群对象，不能把单库 dump 当作整个集群的备份。命令语义参见 [PostgreSQL pg_dump](https://www.postgresql.org/docs/16/app-pgdump.html) 与 [pg_restore](https://www.postgresql.org/docs/16/app-pgrestore.html)。本仓库没有替生产执行备份、恢复、切流或删除旧库。

把 MIO_ADMIN_PG_* 明确指向恢复目标，再运行：

```bash
./scripts/admin.sh recover-restored-runtime
./scripts/admin.sh recover-restored-runtime \
  --apply --expected '恢复目标预览返回的64位摘要' --reason '恢复备份后隔离旧运行身份' --offline
```

恢复维护将 ACTIVE 目标暂停，保留用户表达、验收条件与控制版本历史；已暂停或关闭的目标不被重新打开。所有运行中业务任务有副作用时转 UNKNOWN，无副作用时转 FAILED，待批准任务转 STALE 并清除旧批准。排队但未启动的任务保留，旧领取解除；已经启动的队列任务不会重放。租约 owner 被清除，generation 增加，即使备份中租约的过期时间仍在未来也不能继续使用。账号操作占位和原回执保留，未知操作仍阻止重复提交。

运行中的记忆作业保留检查点与尝试次数，撤销旧 owner/generation 并将租约置零；正常消费者恢复后按当前配置判断是否耗尽次数，未耗尽时领取下一代次。前台和记忆后台的 PENDING 用量转为 UNKNOWN，累计调用次数、输入字符、已报告 token 均不退回。已知与既有未知用量不被改写。随后检查只读诊断、目标状态、待核查操作和审计；恢复目标需要用户显式恢复，业务任务仍须显式继续与重新批准。

## 构建与验收来源

`./scripts/eval-postgres.sh` 创建带 mio.evaluation=true 标签且只绑定回环地址的临时 PostgreSQL 16 + pgvector 容器，完成后删除容器。维护测试只在明确的测试库随机 schema 中运行，真实 dump/restore 用例另建随机数据库；临时备份文件在 finally 中清理。用例验证未来旧租约、未知操作和账号占位、旧审批失效、未启动队列、两套用量账本，以及原文、人工画像/删除标记、向量和结果检查点。另测陈旧预览、并发重试和审计失败回滚。

完整验收要求十个 PG 套件执行且零跳过，含真实 dump/restore 及前台模型用量恢复。仅配置 MIO_TEST_PG_URL 而没有脚本持有的容器身份时，恢复用例跳过，不能得到 OFFLINE_COMPLETE。单独运行 `./scripts/eval.sh` 允许缺数据库，状态保持 POSTGRES_REQUIRED。

验收开始生成新的运行编号、Git revision 与源码指纹。Maven build-info 将这些信息、6.0.0 和 Java 17 编译目标写入 JAR 的 META-INF/build-info.properties。target/evaluation/release.json 记录产物路径及 SHA-256；acceptance.json 将产物、同次 Java/Python 报告和源码指纹绑定。替换 JAR、混入旧构建元数据或缺少产物证据均不能完成验收。普通 Maven 构建的来源默认 unverified，不自行获得验收资格。Git revision 提供历史定位，源码指纹才覆盖构建时实际代码，不能仅凭 revision 推断工作区无修改。发布时应一并保留 JAR、release.json 和验收报告。

OFFLINE_COMPLETE 表示本次受控工程验收完成。真实模型质量、供应商端恢复点核对、生产规模下锁表耗时、恢复时间目标与跨主机故障切换仍需真实环境证据；本模块不创建付费采样或自动上线流程。

本轮在代码提交 9175749 上执行完整入口：287 项 Java、51 项 Python，失败、错误和跳过均为零，九个 PG 套件全部执行，状态 OFFLINE_COMPLETE，资格仍为 NOT_ESTABLISHED。运行编号为 2ed7227d-790b-40f1-b9ef-8297001ab159；产物 mio-6.0.0.jar 的 SHA-256 为 9cdfb9dd420cc0a9a8294bc7c21bd01146077de65d236bacb7441b47a0903434。原始报告保留在本地 target/evaluation/acceptance.json、acceptance.md 和 release.json，不将生成产物提交到仓库。
