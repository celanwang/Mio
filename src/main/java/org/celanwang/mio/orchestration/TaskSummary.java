package org.celanwang.mio.orchestration;

/** UI and supervisor view. Approval decisions and SDK checkpoints remain server-side. */
public record TaskSummary(String id,String key,String agent,String objective,TaskStatus status,String reply,
                          long goalRevision,boolean verificationRequired,int pendingApprovals,java.util.List<OperationRecord> operations,java.util.List<TaskFact> facts,String questionId) {
    public static TaskSummary of(TaskRecord task,AgentRegistry registry){
        String name;
        try{name=registry.require(task.spec().capabilityId()).name();}catch(IllegalArgumentException removed){name=task.spec().capabilityId();}
        String reply=task.status()==TaskStatus.UNKNOWN&&!task.unresolvedEffects()
                ?"执行已中断，业务结果已通过回执核实；请查看操作记录，不重跑本任务。":task.reply();
        return new TaskSummary(task.id(),task.spec().key(),name,task.spec().objective(),
                task.status(),reply,task.goalRevision(),task.unresolvedEffects(),
                (int)task.approvals().stream().filter(a->a.decision()==null).count(),task.operations(),TaskFacts.of(task),task.questionId());
    }
}
