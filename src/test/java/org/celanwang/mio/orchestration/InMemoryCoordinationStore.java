package org.celanwang.mio.orchestration;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Test double only: production never falls back to volatile coordination state. */
final class InMemoryCoordinationStore implements CoordinationStore {
    final Map<String,GoalRecord> goals=new ConcurrentHashMap<>();
    final Map<String,TaskRecord> records=new ConcurrentHashMap<>();
    final List<String> events=new ArrayList<>();
    @Override public Optional<GoalRecord> goal(String session){return Optional.ofNullable(goals.get(session));}
    @Override public synchronized void createGoal(GoalRecord g){if(goals.putIfAbsent(g.sessionId(),g)!=null)throw new IllegalStateException("duplicate goal");}
    @Override public synchronized void saveGoal(GoalRecord old,GoalRecord next){if(!goals.replace(old.sessionId(),old,next))throw new IllegalStateException("stale goal");}
    @Override public List<TaskRecord> tasks(String session){return records.values().stream().filter(t->t.sessionId().equals(session)).sorted(Comparator.comparing(TaskRecord::id)).toList();}
    @Override public Optional<TaskRecord> task(String id){return Optional.ofNullable(records.get(id));}
    @Override public synchronized void createTask(TaskRecord t){if(records.putIfAbsent(t.id(),t)!=null)throw new IllegalStateException("duplicate");}
    @Override public synchronized void saveTask(TaskRecord old,TaskRecord next,String type){if(!records.replace(old.id(),old,next))throw new IllegalStateException("stale task");events.add(type);}
    @Override public synchronized void startOperation(TaskRecord expected,OperationRecord operation,long now){
        if(goals.get(expected.sessionId()).revision()!=expected.goalRevision())throw new IllegalStateException("stale goal");
        if(tasks(expected.sessionId()).stream().anyMatch(t->t.spec().capabilityId().equals(expected.spec().capabilityId())&&t.effectStarted()))
            throw new IllegalStateException("unverified domain effect");
        saveTask(expected,expected.withOperation(operation,now),"operation_started");
    }
}
