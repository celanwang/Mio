package org.celanwang.mio.orchestration;

import org.celanwang.mio.security.PolicyEngine;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class CapabilityInputContractTest {
    final InMemoryCoordinationStore store=new InMemoryCoordinationStore();
    final AgentCapability capability=new AgentCapability("domain","领域","desc","skill","v1",List.of(),CapabilityInputContract.contextual());
    final AtomicInteger workers=new AtomicInteger();
    final TaskRuntime runtime=new TaskRuntime(store,new AgentRegistry(List.of(capability)),(t,g,i,e)->{workers.incrementAndGet();return Mono.just(new TaskWorker.Result("done",false,List.of(),null));},new PolicyEngine("shadow"),Clock.systemUTC(),16);
    final RunBudget budget=new RunBudget(new RunBudget.Limits(20,20,20,10000,10000,10000),Clock.systemUTC());
    CapabilityInputContractTest(){runtime.captureUser("root","test");}
    TaskSpec spec(String contract,List<InputBinding> inputs,TaskScope scope){return new TaskSpec("consumer","domain","analyze",null,inputs.stream().map(InputBinding::taskId).distinct().toList(),scope,inputs,contract);}
    @Test void missingUnknownAndScopeIncompatibleContractsRejectBeforeTaskCreation(){
        for(var invalid:List.of(spec("ride-quote-context",List.of(),TaskScope.PREPARE),spec("invented",List.of(),TaskScope.PREPARE),
                spec("ride-quote-context",List.of(new InputBinding("s","f","v","rideQuote")),TaskScope.REQUEST_APPROVAL),
                spec("default",List.of(new InputBinding("s","f","v","rideQuote")),TaskScope.PREPARE),
                spec("ride-quote-context",List.of(new InputBinding("s","f","v","wrongAlias")),TaskScope.PREPARE))){
            assertThatThrownBy(()->runtime.submit("root",invalid,budget).block()).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(store.tasks("root")).isEmpty();assertThat(workers.get()).isZero();
    }
    @Test void evidenceOfTheWrongTypeCannotSatisfyARequiredAlias(){
        var goal=runtime.goal("root");
        var source=new TaskRecord("s","root",goal.id(),goal.revision(),new TaskSpec("s","domain","source",null,null,TaskScope.PREPARE),"child",capability.executionVersion(),TaskStatus.RUNNING,"",null,List.of(),false,List.of(),0,0,0);
        var op=new OperationRecord("op","c","write","h",1,"SUBMITTING","");
        source=source.withOperation(op,1).withReceipt("op",new OperationReceipt("receipt","test","SUCCESS","raw",OperationOutcome.CONFIRMED_APPLIED,"resource",2),2).next(TaskStatus.COMPLETED,"done",List.of(),true,3);
        store.createTask(source);var fact=TaskFacts.of(source).get(0);
        var result=runtime.submit("root",spec("ride-quote-context",List.of(new InputBinding("s",fact.id(),fact.version(),"rideQuote")),TaskScope.PREPARE),budget).block();
        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);assertThat(result.reply()).contains("事实类型");assertThat(workers.get()).isZero();
    }
    @Test void contractChangesInvalidatePendingTasksEvenWhenSkillDigestIsTheSame(){
        var waiting=runtime.submit("root",new TaskSpec("pending","domain","wait",null,null,TaskScope.PREPARE),budget).block();
        // Construct a durable pending task from the same capability, then change only its contract.
        var pending=new TaskRecord("pending","root",waiting.goalId(),waiting.goalRevision(),new TaskSpec("pending-other","domain","wait",null,null,null),"child",capability.executionVersion(),TaskStatus.NEEDS_INPUT,"question",null,List.of(),false,List.of(),0,0,0);store.createTask(pending);
        var changed=new AgentCapability("domain","领域","desc","skill","v1",List.of(),List.of(new CapabilityInputContract("default","new requirements",Map.of("rideQuote","ride_quote"),true)));
        assertThat(changed.executionVersion()).isNotEqualTo(capability.executionVersion());
        var next=new TaskRuntime(store,new AgentRegistry(List.of(changed)),(t,g,i,e)->Mono.never(),new PolicyEngine("shadow"),Clock.systemUTC(),16);
        assertThat(next.require("root",pending.id()).status()).isEqualTo(TaskStatus.STALE);
        assertThat(new AgentRegistry(List.of(capability)).discover().get(0).capability().inputContracts()).hasSize(3);
    }
}
