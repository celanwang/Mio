package org.celanwang.mio.orchestration;

import java.util.*;

/** Test double only. Production always persists reservations in PostgreSQL. */
final class InMemoryGoalBudgetStore implements GoalBudgetStore {
    final Map<String,Snapshot> states=new HashMap<>();final Map<String,Object[]> calls=new HashMap<>();
    public synchronized Snapshot open(String id,Limits limits,boolean historyUnknown){return states.computeIfAbsent(id,k->new Snapshot(id,limits,0,0,0,0,0,0,0,historyUnknown));}
    public synchronized Optional<Snapshot> find(String id){return Optional.ofNullable(states.get(id));}
    public synchronized String reserve(String id,Kind kind){
        var s=states.get(id);var l=s.limits();
        if(s.knownTokens()>=l.tokens()||kind==Kind.MODEL&&s.models()>=l.models()||kind==Kind.TOOL&&s.tools()>=l.tools()||kind==Kind.TASK&&s.tasks()>=l.tasks())throw new RunBudget.BudgetExceeded("goal exhausted");
        String call=UUID.randomUUID().toString();
        states.put(id,new Snapshot(id,l,s.models()+(kind==Kind.MODEL?1:0),s.tools()+(kind==Kind.TOOL?1:0),s.tasks()+(kind==Kind.TASK?1:0),s.knownTokens(),s.pendingUsage()+(kind==Kind.MODEL?1:0),s.unknownUsage(),s.revision()+1,s.historyUnknown()));
        if(kind==Kind.MODEL)calls.put(call,new Object[]{id,"PENDING",null});return call;
    }
    public synchronized void settle(String id,String call,Long tokens,boolean complete){
        var entry=calls.get(call);if(entry==null||!entry[0].equals(id))throw new IllegalArgumentException("foreign call");
        String state=complete?"KNOWN":"UNKNOWN";
        if(!entry[1].equals("PENDING")){if(entry[1].equals(state)&&Objects.equals(entry[2],tokens))return;throw new IllegalStateException("settled");}
        var s=states.get(id);states.put(id,new Snapshot(id,s.limits(),s.models(),s.tools(),s.tasks(),s.knownTokens()+(tokens==null?0:tokens),s.pendingUsage()-1,s.unknownUsage()+(complete?0:1),s.revision()+1,s.historyUnknown()));
        calls.put(call,new Object[]{id,state,tokens});
    }
}
