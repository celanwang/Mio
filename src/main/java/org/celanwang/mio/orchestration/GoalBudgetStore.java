package org.celanwang.mio.orchestration;

import java.util.Optional;

/** Durable reservations; counts are never refunded just because a request disconnects. */
public interface GoalBudgetStore {
    enum Kind { MODEL, TOOL, TASK }
    record Limits(int models,int tools,int tasks,long tokens) {
        public Limits {if(models<1||tools<1||tasks<1||tokens<1)throw new IllegalArgumentException("目标预算必须为正数");}
    }
    record Snapshot(String goalId,Limits limits,int models,int tools,int tasks,long knownTokens,int pendingUsage,int unknownUsage,long revision,boolean historyUnknown) {
        public Snapshot(String goalId,Limits limits,int models,int tools,int tasks,long knownTokens,int pendingUsage,int unknownUsage,long revision){this(goalId,limits,models,tools,tasks,knownTokens,pendingUsage,unknownUsage,revision,false);}
        public Snapshot {
            if(goalId==null||goalId.isBlank()||limits==null||models<0||models>limits.models()||tools<0||tools>limits.tools()
                    ||tasks<0||tasks>limits.tasks()||knownTokens<0||pendingUsage<0||unknownUsage<0||(long)pendingUsage+unknownUsage>models||revision<0)
                throw new IllegalArgumentException("目标预算状态无效");
        }
        @com.fasterxml.jackson.annotation.JsonProperty(value="usageComplete",access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public boolean usageComplete(){return !historyUnknown&&pendingUsage==0&&unknownUsage==0;}
    }
    default Snapshot open(String goalId,Limits limits){return open(goalId,limits,false);}
    Snapshot open(String goalId,Limits limits,boolean historyUnknown);
    Optional<Snapshot> find(String goalId);
    String reserve(String goalId,Kind kind);
    void settle(String goalId,String callId,Long knownTokens,boolean complete);
}
