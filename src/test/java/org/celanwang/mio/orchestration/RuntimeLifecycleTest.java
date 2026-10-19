package org.celanwang.mio.orchestration;

import org.celanwang.mio.model.dto.ChatRequest;
import org.celanwang.mio.model.vo.ToolConfirmation;
import org.celanwang.mio.security.PolicyEngine;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class RuntimeLifecycleTest {
    final InMemoryCoordinationStore store=new InMemoryCoordinationStore();
    final AgentRegistry registry=new AgentRegistry(List.of(new AgentCapability("domain","领域","描述","skill","1",List.of())));
    final RunBudget budget=new RunBudget(new RunBudget.Limits(30,50,20,100000,100000,120000),Clock.systemUTC());
    TaskSpec spec(String key){return new TaskSpec(key,"domain","目标",null,null,TaskScope.REQUEST_APPROVAL);}
    TaskRuntime runtime(TaskWorker worker){var r=new TaskRuntime(store,registry,worker,new PolicyEngine("shadow"),Clock.systemUTC(),20);r.captureUser("root","用户目标");return r;}
    @Test void cancellingInFlightReadTaskPreventsLateResultFromOverwritingCancellation(){
        var sink=Sinks.<TaskWorker.Result>one();var lease=new AtomicReference<TaskExecution>();
        var r=runtime((task,goal,input,execution)->{lease.set(execution);return sink.asMono();});
        var subscription=r.submit("root",spec("x"),budget).subscribe();
        var id=r.list("root").get(0).id();r.cancel("root",id);
        assertThatThrownBy(()->lease.get().active()).isInstanceOf(IllegalStateException.class);
        sink.tryEmitValue(new TaskWorker.Result("late",false,List.of(),null));
        assertThat(r.require("root",id).status()).isEqualTo(TaskStatus.CANCELLED);subscription.dispose();
    }
    @Test void interruptedTaskIsClassifiedWithoutReplayingTheWorker(){
        var sink=Sinks.<TaskWorker.Result>one();var starts=new AtomicInteger();
        var first=runtime((t,g,i,e)->{starts.incrementAndGet();return sink.asMono();});
        var subscription=first.submit("root",spec("x"),budget).subscribe();
        String id=first.list("root").get(0).id();subscription.dispose();
        var recovered=new TaskRuntime(store,registry,(t,g,i,e)->{starts.incrementAndGet();return Mono.just(new TaskWorker.Result("must not run",false,List.of(),null));},new PolicyEngine("shadow"),Clock.systemUTC(),20);
        assertThat(recovered.continueTask("root",id,budget).block().status()).isEqualTo(TaskStatus.FAILED);
        assertThat(starts.get()).isEqualTo(1);
    }
    @Test void partialBatchDecisionsWaitUntilEveryCurrentCallHasAChoice(){
        var starts=new AtomicInteger();
        var r=runtime((t,g,i,e)->{
            if(starts.incrementAndGet()==1)return Mono.just(new TaskWorker.Result("confirm",false,List.of(
                    new ToolConfirmation("call-a","write-a","description",Map.of("n",1),true,"contract"),
                    new ToolConfirmation("call-b","write-b","description",Map.of("n",2),true,"contract")),null));
            assertThat(i.decisions()).containsExactly(new ChatRequest.Confirmation("call-a",true),new ChatRequest.Confirmation("call-b",false));
            return Mono.just(new TaskWorker.Result("done",false,List.of(),null));
        });
        var task=r.submit("root",spec("batch"),budget).block();var a=task.approvals().get(0);var b=task.approvals().get(1);
        r.approve("root",List.of(new ChatRequest.Confirmation(a.id(),true)),budget).block();assertThat(starts.get()).isEqualTo(1);
        assertThatThrownBy(()->r.approve("root",List.of(new ChatRequest.Confirmation(a.id(),true)),budget).block()).isInstanceOf(IllegalStateException.class);
        r.approve("root",List.of(new ChatRequest.Confirmation(b.id(),false)),budget).block();assertThat(starts.get()).isEqualTo(2);
        assertThat(r.require("root",task.id()).status()).isEqualTo(TaskStatus.COMPLETED);
    }
    @Test void existingTaskKeyCannotChangeScopeOrParameters(){
        var r=runtime((t,g,i,e)->Mono.just(new TaskWorker.Result("done",false,List.of(),null)));
        r.submit("root",spec("same"),budget).block();
        assertThatThrownBy(()->r.submit("root",new TaskSpec("same","domain","不同目标",null,null,TaskScope.PREPARE),budget).block()).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void expiredApprovalBecomesStaleInsteadOfRemainingAnUnusableWaitingTask(){
        var time=new AtomicLong(0);
        Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochMilli(time.get());}};
        var calls=new AtomicInteger();
        TaskWorker worker=(t,g,i,e)->{calls.incrementAndGet();return Mono.just(new TaskWorker.Result("confirm",false,List.of(new ToolConfirmation("call","write","desc",Map.of(),true,"contract")),null));};
        var r=new TaskRuntime(store,registry,worker,new PolicyEngine("shadow"),clock,20);r.captureUser("root","目标");
        var task=r.submit("root",spec("ttl"),budget).block();time.set(300000);
        assertThat(r.list("root").get(0).status()).isEqualTo(TaskStatus.STALE);
        assertThatThrownBy(()->r.approve("root",List.of(new ChatRequest.Confirmation(task.approvals().get(0).id(),true)),budget).block()).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void changedSkillVersionCannotRestoreAnOldTask(){
        var r=runtime((t,g,i,e)->Mono.just(new TaskWorker.Result("question",true,List.of(),null)));
        var task=r.submit("root",spec("version"),budget).block();
        var nextRegistry=new AgentRegistry(List.of(new AgentCapability("domain","领域","描述","skill","2",List.of())));
        var restored=new TaskRuntime(store,nextRegistry,(t,g,i,e)->Mono.error(new AssertionError("must not run")),new PolicyEngine("shadow"),Clock.systemUTC(),20);
        assertThat(restored.require("root",task.id()).status()).isEqualTo(TaskStatus.STALE);
    }
    @Test void failedDependencyStopsTheDependentWithoutStartingItsWorker(){
        var starts=new AtomicInteger();var r=runtime((t,g,i,e)->{starts.incrementAndGet();return Mono.error(new IllegalStateException("failed"));});
        var parent=r.submit("root",spec("parent"),budget).block();
        var child=r.submit("root",new TaskSpec("child","domain","后续",null,List.of(parent.id()),TaskScope.PREPARE),budget).block();
        assertThat(child.status()).isEqualTo(TaskStatus.FAILED);assertThat(starts.get()).isEqualTo(1);
    }
    @Test void finishOrQuestionClosesTheLeaseForAllLaterBusinessTools(){
        var r=runtime((t,g,i,e)->{
            e.finish("done");
            assertThatThrownBy(e::businessActive).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->e.requestInput("late question")).isInstanceOf(IllegalStateException.class);
            return Mono.just(new TaskWorker.Result("done",false,List.of(),null));
        });
        assertThat(r.submit("root",spec("close"),budget).block().status()).isEqualTo(TaskStatus.COMPLETED);
    }
    @Test void approvalExpiryIsCheckedAgainAtTheActualEffectBoundary(){
        var time=new AtomicLong(0);
        Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochMilli(time.get());}};
        var starts=new AtomicInteger();
        TaskWorker worker=(t,g,i,e)->{
            if(starts.incrementAndGet()==1)return Mono.just(new TaskWorker.Result("confirm",false,List.of(new ToolConfirmation("call","create-order","desc",Map.of(),true,"contract")),null));
            time.set(300000);
            var call=io.agentscope.core.message.ToolUseBlock.builder().id("call").name("create-order").input(Map.of()).build();
            assertThatThrownBy(()->e.beforeEffect(call,"contract")).isInstanceOf(IllegalStateException.class);
            return Mono.just(new TaskWorker.Result("expired",false,List.of(),null,true));
        };
        var r=new TaskRuntime(store,registry,worker,new PolicyEngine("shadow"),clock,20);r.captureUser("root","目标");
        var task=r.submit("root",spec("expiry-at-effect"),budget).block();
        var ended=r.approve("root",List.of(new ChatRequest.Confirmation(task.approvals().get(0).id(),true)),budget).block().get(0);
        assertThat(ended.status()).isEqualTo(TaskStatus.FAILED);assertThat(ended.effectStarted()).isFalse();
    }
    @Test void storedGoalRevisionProtectsApprovalsEvenWhenInvalidationWasInterrupted(){
        var r=runtime((t,g,i,e)->Mono.just(new TaskWorker.Result("confirm",false,List.of(new ToolConfirmation("call","write","desc",Map.of(),true,"contract")),null)));
        var task=r.submit("root",spec("old-goal"),budget).block();
        var goal=store.goal("root").orElseThrow();store.saveGoal(goal,goal.appendUser("changed",1));
        assertThat(r.require("root",task.id()).status()).isEqualTo(TaskStatus.STALE);
    }
    @Test void listingAfterProcessLossExposesUncertainSubmissionWithoutReplayingIt(){
        var r=runtime((t,g,i,e)->{
            store.startOperation(t,new OperationRecord(t.id()+":c","c","create-order","hash",1,"SUBMITTING",""),1);
            return Mono.never();
        });
        var subscription=r.submit("root",spec("lost"),budget).subscribe();subscription.dispose();
        var recovered=new TaskRuntime(store,registry,(t,g,i,e)->Mono.error(new AssertionError("must not replay")),new PolicyEngine("shadow"),Clock.systemUTC(),20);
        var task=recovered.list("root").get(0);
        assertThat(task.status()).isEqualTo(TaskStatus.UNKNOWN);assertThat(task.operations().get(0).phase()).isEqualTo("SUBMITTING");
        assertThat(recovered.list("root").get(0)).isEqualTo(task);
    }
    @Test void dependencyCompletionEnablesExactlyOneContinuation(){
        var parentResult=Sinks.<TaskWorker.Result>one();var starts=new AtomicInteger();
        var r=runtime((t,g,i,e)->{starts.incrementAndGet();return t.spec().key().equals("parent")?parentResult.asMono():Mono.just(new TaskWorker.Result("done",false,List.of(),null));});
        var subscription=r.submit("root",spec("parent"),budget).subscribe();String parent=r.list("root").get(0).id();
        var child=r.submit("root",new TaskSpec("child","domain","后续",null,List.of(parent),TaskScope.PREPARE),budget).block();
        assertThat(child.status()).isEqualTo(TaskStatus.WAITING_DEPENDENCY);assertThat(starts.get()).isEqualTo(1);
        parentResult.tryEmitValue(new TaskWorker.Result("done",false,List.of(),null));
        assertThat(r.continueTask("root",child.id(),budget).block().status()).isEqualTo(TaskStatus.COMPLETED);
        assertThatThrownBy(()->r.continueTask("root",child.id(),budget).block()).isInstanceOf(IllegalStateException.class);
        assertThat(starts.get()).isEqualTo(2);subscription.dispose();
    }
}
