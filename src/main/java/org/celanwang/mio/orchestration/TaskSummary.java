package org.celanwang.mio.orchestration;

/** UI and supervisor view. Approval decisions and SDK checkpoints remain server-side. */
public record TaskSummary(String id,String key,String agent,String objective,TaskStatus status,String reply,
                          long goalRevision,boolean verificationRequired,int pendingApprovals,java.util.List<OperationRecord> operations,java.util.List<TaskFact> facts,String questionId,
                          TaskInvalidation invalidation,java.util.List<String> dependencies,java.util.List<InputBinding> inputs,String inputContract,TaskSpec.Dispatch dispatch,TaskQueue.View queue,java.util.List<String> reconcilableOperations,TaskResultAssessment resultAssessment) {
    public TaskSummary withReconcilable(java.util.List<String> ids){return new TaskSummary(id,key,agent,objective,status,reply,goalRevision,verificationRequired,pendingApprovals,operations,facts,questionId,invalidation,dependencies,inputs,inputContract,dispatch,queue,java.util.List.copyOf(ids),resultAssessment);}
    public static TaskSummary of(TaskRecord task,AgentRegistry registry){return of(task,registry,null);}
    public static TaskSummary of(TaskRecord task,AgentRegistry registry,TaskQueue.View queue){
        String name;
        try{name=registry.require(task.spec().capabilityId()).name();}catch(IllegalArgumentException removed){name=task.spec().capabilityId();}
        String reply=task.status()==TaskStatus.UNKNOWN&&!task.unresolvedEffects()
                ?"执行已中断，业务结果已通过回执核实；请查看操作记录，不重跑本任务。":task.reply();
        if(task.invalidation()!=null)reply="当前结果已失效："+task.invalidation().message()+"。历史操作记录保留，不重跑已提交动作。\n"+reply;
        if(reply.length()>2000)reply=reply.substring(0,2000)+"\n[工作报告节选，完整报告按任务编号读取]";
        var operations=task.operations().stream().map(op->new OperationRecord(op.id(),op.callId(),op.tool(),op.inputHash(),op.startedAt(),op.phase(),"",op.outcome(),op.businessId(),op.receipts().stream().skip(Math.max(0,op.receipts().size()-3)).map(r->new OperationReceipt(r.id(),r.source(),r.frameworkState(),"",r.outcome(),r.businessId(),r.observedAt())).toList(),op.logicalId(),op.scopeKey())).toList();
        return new TaskSummary(task.id(),task.spec().key(),name,task.spec().objective(),
                task.status(),reply,task.goalRevision(),task.unresolvedEffects(),
                (int)task.approvals().stream().filter(a->a.decision()==null).count(),operations,TaskFacts.validAt(task,System.currentTimeMillis()),task.questionId(),task.invalidation(),task.spec().dependencies(),task.spec().inputs(),task.spec().inputContract(),task.spec().dispatch(),queue,java.util.List.of(),TaskResultAssessment.current(task,registry,System.currentTimeMillis()));
    }
}
