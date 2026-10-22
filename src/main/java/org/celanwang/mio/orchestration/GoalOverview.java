package org.celanwang.mio.orchestration;

import java.util.List;

/** Read-only operational view. Completion, side-effect verification and qualification stay distinct. */
public record GoalOverview(String id,GoalRecord.State state,long revision,long controlRevision,
                           int queued,int running,int questions,int approvals,int unresolved,int invalidated,int failed,
                           String mode,String qualification) {
    public static GoalOverview of(GoalRecord goal,List<TaskRecord> tasks){return new GoalOverview(goal.id(),goal.state(),goal.revision(),goal.controlRevision(),
            count(tasks,TaskStatus.QUEUED),count(tasks,TaskStatus.RUNNING),count(tasks,TaskStatus.NEEDS_INPUT),
            tasks.stream().filter(t->t.status()==TaskStatus.AWAITING_APPROVAL).mapToInt(t->(int)t.approvals().stream().filter(a->a.decision()==null).count()).sum(),
            (int)tasks.stream().filter(TaskRecord::unresolvedEffects).count(),(int)tasks.stream().filter(t->t.invalidation()!=null||t.status()==TaskStatus.STALE).count(),count(tasks,TaskStatus.FAILED),
            "FOREGROUND_CONTROLLED_PREVIEW","NOT_ESTABLISHED");}
    private static int count(List<TaskRecord> tasks,TaskStatus status){return (int)tasks.stream().filter(t->t.status()==status).count();}
}
