package org.celanwang.mio.orchestration;

import java.util.Optional;

/** Durable foreground dispatch. A started task is never automatically replayed. */
public interface TaskQueue {
    enum State { READY,CLAIMED,STARTED,DONE,HANDED_OFF,DEAD,CANCELLED,STALE;
        public boolean terminal(){return this==DONE||this==HANDED_OFF||this==DEAD||this==CANCELLED||this==STALE;}
    }
    record Entry(String taskId,String sessionId,State state,int attempts,long revision,String claimId,
                 ExecutionLeaseStore.Token lease,String reason,long updatedAt) {
        public Entry {
            if(taskId==null||sessionId==null||state==null||attempts<0||attempts>3||revision<0||claimId==null||reason==null)throw new IllegalArgumentException("队列记录无效");
            if((state==State.CLAIMED||state==State.STARTED)&&(claimId.isBlank()||lease==null))throw new IllegalArgumentException("队列缺少领取身份");
        }
    }
    record Claim(String taskId,String id,ExecutionLeaseStore.Token lease) {}
    record View(State state,int attempts,String reason,long updatedAt) {
        static View of(Entry entry){return new View(entry.state(),entry.attempts(),entry.reason(),entry.updatedAt());}
    }
    void enqueue(TaskRecord task);
    Optional<Entry> find(String taskId);
    Optional<Claim> claim(String taskId,ExecutionLeaseStore.Token lease);
    void start(Claim claim,TaskRecord expected,TaskRecord running);
    Entry settle(Claim claim);
    Entry sync(String taskId);
}
