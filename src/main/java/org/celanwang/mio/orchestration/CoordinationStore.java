package org.celanwang.mio.orchestration;

import java.util.*;

/** Implementations must fail closed, atomically compare revisions, and persist state with an audit event. */
public interface CoordinationStore {
    Optional<GoalRecord> goal(String sessionId);
    void createGoal(GoalRecord goal);
    void saveGoal(GoalRecord expected, GoalRecord next);
    List<TaskRecord> tasks(String sessionId);
    Optional<TaskRecord> task(String id);
    void createTask(TaskRecord task);
    void saveTask(TaskRecord expected, TaskRecord next, String event);
    /** Fence competing submissions and persist the operation before contacting a provider. */
    void startOperation(TaskRecord expected, OperationRecord operation, long now);
    void recordReceipt(String taskId,String operationId,OperationReceipt receipt,long now);
}
