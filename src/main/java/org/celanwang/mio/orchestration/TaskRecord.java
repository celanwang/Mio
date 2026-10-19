package org.celanwang.mio.orchestration;

import java.util.List;

/** Revision is optimistic concurrency control, not model supplied. Effect flag is sticky. */
public record TaskRecord(String id, String sessionId, String goalId, long goalRevision, TaskSpec spec,
                         String childSessionId, TaskStatus status, String reply, List<PendingApproval> approvals,
                         boolean effectStarted, long revision, long createdAt, long updatedAt) {
    public TaskRecord {
        approvals = approvals == null ? List.of() : List.copyOf(approvals);
        reply = reply == null ? "" : reply;
    }
    public TaskRecord next(TaskStatus state, String text, List<PendingApproval> pending, boolean effect, long now) {
        if (status.terminal()) throw new IllegalStateException("任务已结束，不能重新执行");
        if (effectStarted && !effect) throw new IllegalArgumentException("不能抹去已提交的操作");
        return new TaskRecord(id,sessionId,goalId,goalRevision,spec,childSessionId,state,text,pending,
                effect,revision+1,createdAt,now);
    }
}
