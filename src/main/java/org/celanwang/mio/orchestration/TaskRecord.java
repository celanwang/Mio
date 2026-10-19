package org.celanwang.mio.orchestration;

import java.util.List;

/** Revision is optimistic concurrency control, not model supplied. Effect flag is sticky. */
public record TaskRecord(String id, String sessionId, String goalId, long goalRevision, TaskSpec spec,
                         String childSessionId, String capabilityVersion, TaskStatus status, String reply, org.celanwang.mio.model.vo.WeiboAction action, List<PendingApproval> approvals,
                         boolean effectStarted, List<OperationRecord> operations, long revision, long createdAt, long updatedAt) {
    public TaskRecord {
        operations = operations == null ? List.of() : List.copyOf(operations);
        approvals = approvals == null ? List.of() : List.copyOf(approvals);
        reply = reply == null ? "" : reply;
        capabilityVersion = capabilityVersion == null ? "" : capabilityVersion;
    }
    public TaskRecord(String id,String sessionId,String goalId,long goalRevision,TaskSpec spec,String childSessionId,
                      TaskStatus status,String reply,List<PendingApproval> approvals,boolean effectStarted,long revision,long createdAt,long updatedAt) {
        this(id,sessionId,goalId,goalRevision,spec,childSessionId,"",status,reply,null,approvals,effectStarted,List.of(),revision,createdAt,updatedAt);
    }
    public TaskRecord next(TaskStatus state, String text, List<PendingApproval> pending, boolean effect, long now) {
        return next(state,text,pending,effect,action,now);
    }
    public TaskRecord next(TaskStatus state,String text,List<PendingApproval> pending,boolean effect,org.celanwang.mio.model.vo.WeiboAction action,long now) {
        if (status.terminal()) throw new IllegalStateException("任务已结束，不能重新执行");
        if (effectStarted && !effect) throw new IllegalArgumentException("不能抹去已提交的操作");
        return new TaskRecord(id,sessionId,goalId,goalRevision,spec,childSessionId,capabilityVersion,state,text,action,pending,
                effect,operations,revision+1,createdAt,now);
    }
    public TaskRecord withOperation(OperationRecord operation,long now) {
        if(status!=TaskStatus.RUNNING)throw new IllegalStateException("任务不可执行");
        var updated=new java.util.ArrayList<>(operations);
        updated.removeIf(o->o.id().equals(operation.id()));updated.add(operation);
        return new TaskRecord(id,sessionId,goalId,goalRevision,spec,childSessionId,capabilityVersion,status,reply,action,approvals,true,updated,revision+1,createdAt,now);
    }
    public TaskRecord atGoal(GoalRecord goal,long now) {
        if(status!=TaskStatus.NEEDS_INPUT || effectStarted)throw new IllegalStateException("任务不能自动变更目标版本");
        return new TaskRecord(id,sessionId,goalId,goal.revision(),spec,childSessionId,capabilityVersion,status,reply,null,List.of(),false,operations,revision+1,createdAt,now);
    }
}
