package org.celanwang.mio.orchestration;

import org.celanwang.mio.security.PolicyEngine;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class TaskFactBindingTest {
    final InMemoryCoordinationStore store=new InMemoryCoordinationStore();
    final GoalRecord goal=new GoalRecord("g","root",1,"intent","intent",0,0);
    final RunBudget budget=new RunBudget(new RunBudget.Limits(20,20,20,10000,10000,10000),Clock.systemUTC());
    TaskFactBindingTest(){store.createGoal(goal);}
    TaskRecord source(String id,String session){
        var t=new TaskRecord(id,session,"g",1,new TaskSpec(id,"domain","parent",null,null,null),"child","v1",TaskStatus.RUNNING,"",null,List.of(),false,List.of(),0,0,0);
        var operation=new OperationRecord("op-"+id,"call","write","hash",1,"SUBMITTING","");
        t=t.withOperation(operation,1).withReceipt(operation.id(),new OperationReceipt("receipt-"+id,"trusted-test","SUCCESS","raw",OperationOutcome.CONFIRMED_APPLIED,"resource-"+id,2),2)
                .next(TaskStatus.COMPLETED,"only prose",List.of(),true,3);
        store.createTask(t);return t;
    }
    TaskSpec spec(TaskRecord parent,TaskFact fact,String version){return new TaskSpec("consumer","domain","consume",null,List.of(parent.id()),TaskScope.PREPARE,List.of(new InputBinding(parent.id(),fact.id(),version,"order")));}
    TaskRuntime runtime(TaskWorker worker){return new TaskRuntime(store,new AgentRegistry(List.of(new AgentCapability("domain","领域","desc","s","v1",List.of()))),worker,new PolicyEngine("shadow"),Clock.systemUTC(),16);}
    @Test void runtimeDeliversTheStoredValueAndEvidenceToTheWorker(){
        var parent=source("parent","root");var fact=TaskFacts.of(parent).get(0);var starts=new AtomicInteger();
        var runtime=runtime((t,g,i,e)->{
            starts.incrementAndGet();assertThat(i.bindings()).hasSize(1);assertThat(i.bindings().get(0).fact()).isEqualTo(fact);
            assertThat(i.bindings().get(0).fact().value()).containsEntry("businessId","resource-parent");
            return Mono.just(new TaskWorker.Result("done",false,List.of(),null));
        });
        assertThat(runtime.submit("root",spec(parent,fact,fact.version()),budget).block().status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(starts.get()).isEqualTo(1);
    }
    @Test void wrongVersionOrInventedFactNeverStartsTheWorker(){
        var parent=source("parent","root");var fact=TaskFacts.of(parent).get(0);var starts=new AtomicInteger();
        var runtime=runtime((t,g,i,e)->{starts.incrementAndGet();return Mono.error(new AssertionError("must not run"));});
        var result=runtime.submit("root",spec(parent,fact,"invented-version"),budget).block();
        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);assertThat(result.reply()).contains("事实");assertThat(starts.get()).isZero();
        var fake=new InputBinding(parent.id(),"invented-fact",fact.version(),"order");
        assertThat(runtime.submit("root",new TaskSpec("fake","domain","consume",null,List.of(parent.id()),null,List.of(fake)),budget).block().status()).isEqualTo(TaskStatus.FAILED);
    }
    @Test void ordinaryProseAndUnconfirmedReceiptsAreNeverProjectedAsFacts(){
        var parent=source("parent","root");
        var conflict=new OperationReceipt("conflict","trusted-test","SUCCESS","conflict",OperationOutcome.CONFIRMED_APPLIED,"different-resource",4);
        store.recordReceipt(parent.id(),"op-parent",conflict,4);
        assertThat(TaskFacts.of(store.task(parent.id()).orElseThrow())).isEmpty();
    }
    @Test void factChangeFencesRunningToolsAndInvalidatesTheirLateCompletion(){
        var parent=source("parent","root");var fact=TaskFacts.of(parent).get(0);
        var lease=new AtomicReference<TaskExecution>();var sink=Sinks.<TaskWorker.Result>one();
        var runtime=runtime((t,g,i,e)->{lease.set(e);return sink.asMono();});
        var subscription=runtime.submit("root",spec(parent,fact,fact.version()),budget).subscribe();
        store.recordReceipt(parent.id(),"op-parent",new OperationReceipt("conflict","trusted-test","SUCCESS","conflict",OperationOutcome.CONFIRMED_APPLIED,"different-resource",4),4);
        assertThatThrownBy(()->lease.get().businessActive()).isInstanceOf(TaskFacts.InputUnavailable.class);
        sink.tryEmitValue(new TaskWorker.Result("late",false,List.of(),null));
        var consumer=store.tasks("root").stream().filter(t->t.spec().key().equals("consumer")).findFirst().orElseThrow();
        assertThat(consumer.status()).isEqualTo(TaskStatus.STALE);subscription.dispose();
    }
    @Test void crossSessionFactsCannotBeBoundAndUndeclaredDependenciesAreRejected(){
        var parent=source("parent","other");var fact=TaskFacts.of(parent).get(0);
        assertThatThrownBy(()->runtime((t,g,i,e)->Mono.never()).submit("root",spec(parent,fact,fact.version()),budget).block()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new TaskSpec("consumer","domain","goal",null,List.of(),null,List.of(new InputBinding(parent.id(),fact.id(),fact.version(),"order"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
