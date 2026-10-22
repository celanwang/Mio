package org.celanwang.mio.orchestration;

import java.util.*;

/** A bounded repair view. It cannot rebase goals, approve actions, or execute replacement tasks. */
public record PlanReview(long goalRevision,List<Repair> sourceRepairs,List<Repair> affectedTasks,List<String> unaffectedTaskIds) {
    public enum Action { REFRESH_PREPARATION,REBIND_FACT,REPLAN_PREPARATION,REQUIRES_REVIEW,VERIFY_OPERATION }
    public record Repair(String taskId,Action action,TaskSpec previousSpec,List<String> blockedBy,TaskInvalidation cause) {}
    public PlanReview {sourceRepairs=List.copyOf(sourceRepairs);affectedTasks=List.copyOf(affectedTasks);unaffectedTaskIds=List.copyOf(unaffectedTaskIds);}
    static PlanReview of(GoalRecord goal,List<TaskRecord> tasks,long now,CoordinationStore store){
        var current=new LinkedHashMap<String,TaskRecord>();
        tasks.stream().filter(t->t.goalId().equals(goal.id())&&t.goalRevision()==goal.revision()).forEach(t->current.put(t.id(),t));
        var affected=new ArrayList<Repair>();var refresh=new LinkedHashMap<String,Repair>();var unaffected=new ArrayList<String>();
        for(var task:current.values()){
            var cause=task.invalidation();
            if(cause==null){unaffected.add(task.id());continue;}
            var blockers=new LinkedHashSet<String>();
            for(var issue:TaskDependencies.issues(store,task,now)){
                var root=current.get(issue.rootTaskId());
                if(root!=null&&root.invalidation()==null){
                    boolean rebind=root.status()==TaskStatus.COMPLETED&&TaskFacts.validAt(root,now).stream().anyMatch(f->f.id().equals(issue.factId()));
                    var action=rebind?Action.REBIND_FACT:canPrepare(root)?Action.REFRESH_PREPARATION:root.effectStarted()?Action.VERIFY_OPERATION:Action.REQUIRES_REVIEW;
                    refresh.merge(root.id(),new Repair(root.id(),action,root.spec(),root.spec().dependencies(),issue),
                            (first,next)->priority(next.action())>priority(first.action())?next:first);
                }
                blockers.add(issue.sourceTaskId());
            }
            affected.add(new Repair(task.id(),task.effectStarted()?Action.VERIFY_OPERATION:canPrepare(task)?Action.REPLAN_PREPARATION:Action.REQUIRES_REVIEW,
                    task.spec(),List.copyOf(blockers),cause));
        }
        // Refresh sources need attention even though their historical execution remains valid.
        unaffected.removeAll(refresh.keySet());
        return new PlanReview(goal.revision(),List.copyOf(refresh.values()),affected,unaffected);
    }
    private static boolean canPrepare(TaskRecord task){return !task.effectStarted()&&task.spec().scope()==TaskScope.PREPARE
            &&task.status()!=TaskStatus.RUNNING&&task.status()!=TaskStatus.UNKNOWN&&task.status()!=TaskStatus.CANCELLED&&task.status()!=TaskStatus.FAILED;}
    private static int priority(Action action){return switch(action){
        case VERIFY_OPERATION->4;case REQUIRES_REVIEW->3;case REFRESH_PREPARATION->2;case REBIND_FACT->1;case REPLAN_PREPARATION->0;
    };}
}
