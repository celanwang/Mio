package org.celanwang.mio.orchestration;

import java.util.*;

final class GoalAcceptanceFixtures {
    static final AgentCapability CAPABILITY=new AgentCapability("domain","测试能力","按证据验收","skill","v1",List.of(),List.of(),
            List.of(CapabilityOutputContract.report(),CapabilityOutputContract.rideQuote(),CapabilityOutputContract.resourceReference()));
    static final AgentRegistry REGISTRY=new AgentRegistry(List.of(CAPABILITY));
    static TaskRecord task(String id,GoalRecord goal,String contract,TaskScope scope,long now) {
        return new TaskRecord(id,goal.sessionId(),goal.id(),goal.revision(),new TaskSpec(id,"domain","验收任务 "+id,null,null,scope,null,null,null,contract),id+"-child",
                CAPABILITY.executionVersion(),TaskStatus.RUNNING,"",null,List.of(),false,List.of(),0,now,now);
    }
    static TaskRecord quote(String id,GoalRecord goal,long now,String duration) {
        var task=task(id,goal,"ride-quote",TaskScope.PREPARE,now);
        var observation=new FactObservation(id+"-fact","ride.quote","trusted-fixture","query","hash",now,"","",null);
        task=task.withObservation(observation,true,now);
        task=task.withObservation(observation.returned(task,"fixture",new FactExtractor.Value("ride_quote",Map.of("durationSeconds",duration,"distanceMeters","1000.5"),now+60000),now),false,now);
        return task.next(TaskStatus.COMPLETED,"已取得报价",List.of(),false,now);
    }
    static TaskRecord unknown(String id,GoalRecord goal,long now) {
        var task=task(id,goal,"resource-reference",TaskScope.REQUEST_APPROVAL,now);
        return task.withOperation(new OperationRecord(id+"-op","call","create-order","hash",now,"SUBMITTING",""),now)
                .next(TaskStatus.UNKNOWN,"需核查",List.of(),true,now);
    }
    static GoalAcceptance.Requirement requirement(TaskRecord task,List<GoalAcceptance.Check> checks) {
        return new GoalAcceptance.Requirement(task.id(),task.capabilityVersion(),task.spec().outputContract(),CAPABILITY.outputContract(task.spec()).version(),checks);
    }
    static GoalAcceptance.Selection selection(TaskRecord task,List<GoalAcceptance.Check> checks) {
        return new GoalAcceptance.Selection(task.id(),CAPABILITY.outputContract(task.spec()).version(),checks);
    }
    private GoalAcceptanceFixtures(){}
}
