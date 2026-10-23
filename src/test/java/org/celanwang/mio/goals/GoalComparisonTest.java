package org.celanwang.mio.goals;

import org.celanwang.mio.orchestration.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class GoalComparisonTest {
    final AgentCapability capability=new AgentCapability("domain","domain","domain","s","v1",List.of(),List.of(),List.of(CapabilityOutputContract.report(),CapabilityOutputContract.rideQuote()));
    final AgentRegistry registry=new AgentRegistry(List.of(capability));
    GoalProgram program(){return new GoalProgram("g",1,1,Map.of(),List.of(step("a"),step("b"),step("c")),new GoalProgram.Policy(false,System.currentTimeMillis()+10000,1,60,1,Set.of("domain")),Map.of("a",1L,"b",1L,"c",1L));}
    GoalProgram.Step step(String id){return new GoalProgram.Step(id,"domain","route",List.of(),List.of(),"ride-quote",List.of());}
    TaskRecord task(GoalProgram p,String id,String duration,String distance,long expires){
        var step=p.steps().stream().filter(s->s.id().equals(id)).findFirst().orElseThrow();var spec=p.task(step,List.of());
        var base=new TaskRecord(id,"s","g",1,spec,"child"+id,capability.executionVersion(),TaskStatus.RUNNING,"report",null,List.of(),false,List.of(),0,1,1);
        var observation=new FactObservation("fact"+id,"ride.quote","fixture","read","hash",1,"","",null).returned(base,"raw",new FactExtractor.Value("ride_quote",Map.of("durationSeconds",duration,"distanceMeters",distance),expires),System.currentTimeMillis()-100);
        return base.withObservation(new FactObservation("fact"+id,"ride.quote","fixture","read","hash",1,"","",null),true,System.currentTimeMillis()).withObservation(observation,false,System.currentTimeMillis()).next(TaskStatus.COMPLETED,"done",List.of(),false,System.currentTimeMillis());
    }
    @Test void hardConditionsPrecedeWeightedTradeoffsAndExpiredCandidatesAreExplained(){
        var p=program();long until=System.currentTimeMillis()+60000;var tasks=List.of(task(p,"a","10","100",until),task(p,"b","20","50",until),task(p,"c","1","1",System.currentTimeMillis()-1));
        var criteria=List.of(new GoalComparison.Criterion("durationSeconds","秒",GoalComparison.Direction.MIN,new BigDecimal("1"),null,null),new GoalComparison.Criterion("distanceMeters","米",GoalComparison.Direction.MIN,new BigDecimal("3"),null,null));
        var result=GoalComparison.compare(p,tasks,registry,new GoalComparison.Request(1,1,List.of(),criteria));
        assertThat(result.candidates().get(0).stepId()).isEqualTo("b");assertThat(result.candidates().get(0).score()).isEqualByComparingTo("0.75");
        assertThat(result.candidates().get(2).eligible()).isFalse();assertThat(result.candidates().get(2).reasons()).isNotEmpty();
        var constrained=List.of(new GoalComparison.Criterion("durationSeconds","秒",GoalComparison.Direction.MIN,BigDecimal.ONE,GoalAcceptance.Operator.LTE,"15"));
        var filtered=GoalComparison.compare(p,tasks,registry,new GoalComparison.Request(1,1,List.of(),constrained));assertThat(filtered.candidates().get(0).stepId()).isEqualTo("a");assertThat(filtered.candidates().stream().filter(c->c.stepId().equals("b")).findFirst().orElseThrow().reasons()).anyMatch(r->r.contains("硬条件"));
    }
    @Test void incompatibleUnitsAreNotCompared(){var p=program();var result=GoalComparison.compare(p,List.of(task(p,"a","10","100",System.currentTimeMillis()+60000)),registry,new GoalComparison.Request(1,1,List.of("a"),List.of(new GoalComparison.Criterion("durationSeconds","分钟",GoalComparison.Direction.MIN,BigDecimal.ONE,null,null))));assertThat(result.candidates().get(0).eligible()).isFalse();assertThat(result.recommendation()).contains("没有");}
}
