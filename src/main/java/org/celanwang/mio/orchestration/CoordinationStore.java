package org.celanwang.mio.orchestration;

import java.util.*;

/** Implementations must fail closed, atomically compare revisions, and persist state with an audit event. */
public interface CoordinationStore {
    Optional<GoalRecord> goal(String sessionId);
    void createGoal(GoalRecord goal);
    void saveGoal(GoalRecord expected, GoalRecord next);
    List<TaskRecord> tasks(String sessionId);
    default List<TaskRecord> tasksForKeys(String session,long goalRevision,Set<String> keys){return tasks(session).stream().filter(t->t.goalRevision()==goalRevision&&keys.contains(t.spec().key())).toList();}
    default List<TaskRecord> unresolvedTasks(String session){return tasks(session).stream().filter(TaskRecord::unresolvedEffects).toList();}
    default List<TaskRecord> tasksByIds(String session,Set<String> ids){return tasks(session).stream().filter(t->ids.contains(t.id())).toList();}
    default long activeTaskCount(String session){return tasks(session).stream().filter(t->!t.status().terminal()).count();}
    Optional<TaskRecord> task(String id);
    void createTask(TaskRecord task);
    void saveTask(TaskRecord expected, TaskRecord next, String event);
    /** Fence competing submissions and persist the operation before contacting a provider. */
    void startOperation(TaskRecord expected, OperationRecord operation, long now);
    default void startOperation(TaskRecord expected,OperationRecord operation,long now,Runnable beforeCommit){beforeCommit.run();startOperation(expected,operation,now);}
    /** Re-evaluate the dependency graph while fencing task changes; do not trust a caller-authored cause. */
    TaskRecord invalidateDependencies(String taskId,long now);
    /** Persist invalidation before querying and evidence after return, under the current goal/task fence. */
    void recordObservation(String taskId,FactObservation observation,boolean starting,long now);
    void recordReceipt(String taskId,String operationId,OperationReceipt receipt,long now);
}
