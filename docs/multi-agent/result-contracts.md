# 任务输出契约与证据验收

任务循环结束、取得业务证据和满足用户整体目标是三个不同判断。当前实现给任务增加由应用注册、随能力版本变化的输出契约，避免只凭模型的完成声明把需要证据的工作标为完成。

`discover_agents` 发布 capability.outputContracts。主 Agent 只能选择契约编号，不能提供自己的断言、证据值或验证脚本。`delegate_task.outputContract` 写入不可变任务说明，参与同 key 参数一致性检查。输出定义也进入 capability.executionVersion，因此旧待批准任务不能在定义变化后沿用原批准。旧任务 JSON 未包含输出契约时按 report 读取，不把历史工作报告升级为业务证明。

report 只表示工作报告。ride-quote 要求当前任务存在 ride.quote 键、ride_quote 类型的可信事实，并限制为 PREPARE。resource-reference 要求关联回执生成的 operation.business_id 资源事实，并限制为 REQUEST_APPROVAL。目前仅为已有对应可信适配器的出行能力注册两个证据契约；其他领域继续报告工作或保留未知，不能靠新增契约名称假造适配能力。编排运行时按契约检查，不按供应商品牌分支处理。

正常结束任务前，运行时检查其声明的输出要求。缺少证据、证据失效或能力版本变化时不进入 COMPLETED；已产生未知副作用时仍优先保持 UNKNOWN，不能由输出失败改写成未执行。报告字符串不参与判断，也不会代替真实回执。输入依赖仍必须显式绑定，输出契约不会自动推断模型遗漏的依赖。

任务摘要新增 resultAssessment，包含契约定义指纹、当前评估状态、证据 id、事实版本和有效期。VERIFIED 仅说明契约要求的证据当前满足；EXECUTION_ONLY 不证明用户总体目标完成。已完成任务的报价过期后，重新读取会得到 EXPIRED_EVIDENCE，保留历史 COMPLETED 以表达当时工作已经结束。已中断且发出操作的任务，在晚到可信回执满足输出契约后也可显示 VERIFIED，但历史 UNKNOWN 或 FAILED 保持不变，不能据此恢复或重放任务。契约版本变更后返回 CONTRACT_CHANGED，不用新定义重新解释历史成功。界面同步区分这些状态。

这一步没有自动把自然语言意图转成完整验收条件。主 Agent 是否选择了用户真正需要的契约，仍需编排评测检验；report 不能被视作绕过业务证据后的成功资格。目标级条件确认、字段约束比较和确认范围的整体达成状态已由[目标级验收](goal-acceptance.md)接通，不能用所有子任务 COMPLETED 或任一 VERIFIED 代替。资源创建证明也不代表支付、接单或履约完成。

`OutputContractTest` 验证无证据的完成声明不能通过、失败任务不能启动依赖工作、可信报价及过期边界、人工批准和未知回执、旧 JSON 兼容，以及错误范围与契约变更。测试使用真实领域 Agent 循环，模型与外部业务均为替身。
