package org.celanwang.mio.orchestration;

/** UI and supervisor view. Approval decisions and SDK checkpoints remain server-side. */
public record TaskSummary(String id,String key,String agent,String objective,TaskStatus status,String reply,
                          long goalRevision,boolean verificationRequired,int pendingApprovals,java.util.List<OperationRecord> operations) {
    public static TaskSummary of(TaskRecord task,AgentRegistry registry){
        String name;
        try{name=registry.require(task.spec().capabilityId()).name();}catch(IllegalArgumentException removed){name=task.spec().capabilityId();}
        return new TaskSummary(task.id(),task.spec().key(),name,task.spec().objective(),
                task.status(),task.reply(),task.goalRevision(),task.effectStarted(),
                (int)task.approvals().stream().filter(a->a.decision()==null).count(),task.operations());
    }
}
