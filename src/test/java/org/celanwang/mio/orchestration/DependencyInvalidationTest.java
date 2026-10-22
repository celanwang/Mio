package org.celanwang.mio.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.celanwang.mio.security.PolicyEngine;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class DependencyInvalidationTest {
    static final long NOW=1_800_000_000_000L;
    final AtomicLong time=new AtomicLong(NOW);
    final Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochMilli(time.get());}};
    final InMemoryCoordinationStore store=new InMemoryCoordinationStore();
    final GoalRecord goal=new GoalRecord("g","root",1,"intent","intent",NOW,NOW);
    final AgentRegistry registry=new AgentRegistry(List.of(new AgentCapability("domain","领域","desc","s","v1",List.of())));
    final RunBudget budget=new RunBudget(new RunBudget.Limits(50,50,50,10000,10000,10000),clock);
    DependencyInvalidationTest(){store.createGoal(goal);}
    TaskRuntime runtime(TaskWorker worker){return new TaskRuntime(store,registry,worker,new PolicyEngine("shadow"),clock,16);}
    TaskRuntime runtime(){return runtime((t,g,i,e)->Mono.error(new AssertionError("review must never run workers")));}
    TaskRecord task(String id,List<String> deps,List<InputBinding> inputs,TaskStatus status,TaskScope scope){
        var pending=status==TaskStatus.AWAITING_APPROVAL?List.of(new PendingApproval("approval-"+id,"call-"+id,"write","confirm",Map.of(),"h","c",NOW+10000,null)):List.<PendingApproval>of();
        var task=new TaskRecord(id,"root","g",1,new TaskSpec(id,"domain","objective-"+id,List.of(),deps,scope,inputs),"child-"+id,"v1",status,"historical reply",null,pending,false,List.of(),0,NOW,NOW);
        store.createTask(task);return task;
    }
    TaskRecord quote(String id,long expiresAt){
        var running=task(id,List.of(),List.of(),TaskStatus.RUNNING,TaskScope.PREPARE);
        var observation=new FactObservation(id+"-fact","ride.quote","test","query","hash",NOW,"","",null);
        running=running.withObservation(observation,true,NOW);
        running=running.withObservation(observation.returned(running,"synthetic provider evidence",new FactExtractor.Value("ride_quote",Map.of("durationSeconds","60"),expiresAt),NOW),false,NOW);
        var completed=running.next(TaskStatus.COMPLETED,"quoted",List.of(),false,NOW);store.records.put(id,completed);return completed;
    }
    InputBinding input(TaskRecord source){var fact=TaskFacts.of(source).get(0);return new InputBinding(source.id(),fact.id(),fact.version(),"quote");}
    @Test void expiryInvalidatesOnlyTheDependencyConeAndPreservesAnUnrelatedApproval(){
        var source=quote("source",NOW+1);
        var analysis=task("analysis",List.of(source.id()),List.of(input(source)),TaskStatus.COMPLETED,TaskScope.PREPARE);
        var downstream=task("downstream",List.of(analysis.id()),List.of(),TaskStatus.AWAITING_APPROVAL,TaskScope.REQUEST_APPROVAL);
        var unrelated=task("unrelated",List.of(),List.of(),TaskStatus.AWAITING_APPROVAL,TaskScope.REQUEST_APPROVAL);
        time.incrementAndGet();var runtime=runtime();var report=runtime.reviewPlan("root");
        var invalid=store.task(analysis.id()).orElseThrow();assertThat(invalid.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(invalid.invalidation().code()).isEqualTo(TaskInvalidation.Code.FACT_EXPIRED);assertThat(invalid.reply()).isEqualTo("historical reply");
        var child=store.task(downstream.id()).orElseThrow();assertThat(child.status()).isEqualTo(TaskStatus.STALE);assertThat(child.approvals()).isEmpty();
        assertThat(child.invalidation().rootTaskId()).isEqualTo(source.id());assertThat(child.invalidation().sourceTaskId()).isEqualTo(analysis.id());
        assertThat(store.task(unrelated.id()).orElseThrow()).isEqualTo(unrelated);assertThat(report.unaffectedTaskIds()).containsExactly(unrelated.id());
        assertThat(report.sourceRepairs()).singleElement().satisfies(r->{assertThat(r.taskId()).isEqualTo(source.id());assertThat(r.action()).isEqualTo(PlanReview.Action.REFRESH_PREPARATION);});
        assertThat(report.affectedTasks()).extracting(PlanReview.Repair::taskId).containsExactly(analysis.id(),downstream.id());
        int events=store.events.size();assertThat(runtime.reviewPlan("root")).isEqualTo(report);assertThat(store.events).hasSize(events);
        var summary=TaskSummary.of(invalid,registry);assertThat(summary.reply()).contains("当前结果已失效");assertThat(summary.invalidation()).isEqualTo(invalid.invalidation());
        assertThatThrownBy(()->runtime.approve("root",List.of(new org.celanwang.mio.model.dto.ChatRequest.Confirmation("approval-downstream",true)),budget).block()).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void transitiveDependencyWithoutBoundInputsIsFencedBeforeAWorkerOrToolStarts(){
        var source=quote("source",NOW+1);var middle=task("middle",List.of(source.id()),List.of(input(source)),TaskStatus.COMPLETED,TaskScope.PREPARE);
        var leaf=task("leaf",List.of(middle.id()),List.of(),TaskStatus.RUNNING,TaskScope.REQUEST_APPROVAL);
        time.incrementAndGet();
        var execution=new TaskExecution(store,leaf,goal,budget,new PolicyEngine("shadow"),clock,Map.of());
        assertThatThrownBy(execution::businessActive).isInstanceOf(TaskFacts.InputUnavailable.class);
        assertThatThrownBy(()->store.startOperation(leaf,new OperationRecord("op","call","write","h",NOW,"SUBMITTING",""),time.get())).isInstanceOf(TaskFacts.InputUnavailable.class);
        assertThat(store.task(leaf.id()).orElseThrow().effectStarted()).isFalse();
        var starts=new AtomicInteger();var runtime=runtime((t,g,i,e)->{starts.incrementAndGet();return Mono.never();});
        var result=runtime.submit("root",new TaskSpec("new-leaf","domain","prepare",null,List.of(middle.id()),TaskScope.PREPARE),budget).block();
        assertThat(result.status()).isEqualTo(TaskStatus.STALE);assertThat(starts.get()).isZero();
    }
    @Test void committedOperationsAndLateReceiptsSurviveInvalidationAndAreNeverRepairCandidates(){
        var source=quote("source",NOW+1);var leaf=task("leaf",List.of(source.id()),List.of(input(source)),TaskStatus.RUNNING,TaskScope.REQUEST_APPROVAL);
        var op=new OperationRecord("op","call","write","h",NOW,"SUBMITTING","");store.startOperation(leaf,op,NOW);
        time.incrementAndGet();var invalid=store.invalidateDependencies(leaf.id(),time.get());
        assertThat(invalid.status()).isEqualTo(TaskStatus.UNKNOWN);assertThat(invalid.effectStarted()).isTrue();assertThat(invalid.operations()).hasSize(1);
        store.recordReceipt(leaf.id(),op.id(),new OperationReceipt("receipt","test","SUCCESS","evidence",OperationOutcome.CONFIRMED_APPLIED,"business",time.get()),time.get());
        var updated=store.task(leaf.id()).orElseThrow();assertThat(updated.invalidation()).isEqualTo(invalid.invalidation());assertThat(updated.unresolvedEffects()).isFalse();
        assertThat(TaskFacts.of(updated)).isEmpty();assertThat(updated.operations().get(0).businessId()).isEqualTo("business");
        assertThat(runtime().reviewPlan("root").affectedTasks()).singleElement().satisfies(r->assertThat(r.action()).isEqualTo(PlanReview.Action.VERIFY_OPERATION));
    }
    @Test void cancellationPropagatesButDoesNotRecommendRestartingTheCancelledSource(){
        var source=task("source",List.of(),List.of(),TaskStatus.NEEDS_INPUT,TaskScope.PREPARE);
        task("child",List.of(source.id()),List.of(),TaskStatus.WAITING_DEPENDENCY,TaskScope.PREPARE);
        var runtime=runtime();runtime.cancel("root",source.id());var report=runtime.reviewPlan("root");
        assertThat(report.sourceRepairs()).singleElement().satisfies(r->assertThat(r.action()).isEqualTo(PlanReview.Action.REQUIRES_REVIEW));
        assertThat(store.task("child").orElseThrow().status()).isEqualTo(TaskStatus.STALE);
        assertThat(store.task("child").orElseThrow().invalidation().code()).isEqualTo(TaskInvalidation.Code.DEPENDENCY_FAILED);
    }
    @Test void unfinishedDependenciesWaitWithoutFalseInvalidation(){
        var source=task("source",List.of(),List.of(),TaskStatus.NEEDS_INPUT,TaskScope.PREPARE);
        var child=task("child",List.of(source.id()),List.of(),TaskStatus.WAITING_DEPENDENCY,TaskScope.PREPARE);
        assertThat(runtime().require("root",child.id())).isEqualTo(child);
        assertThat(runtime().reviewPlan("root").affectedTasks()).isEmpty();
    }
    @Test void invalidationIsStickyAndSurvivesJsonReloadIncludingCompletedHistory()throws Exception{
        var source=quote("source",NOW+1);var child=task("child",List.of(source.id()),List.of(input(source)),TaskStatus.COMPLETED,TaskScope.PREPARE);
        time.incrementAndGet();var invalid=store.invalidateDependencies(child.id(),time.get());var json=new ObjectMapper();
        assertThat(json.readValue(json.writeValueAsString(invalid),TaskRecord.class)).isEqualTo(invalid);
        time.set(NOW);assertThat(store.invalidateDependencies(child.id(),time.get())).isEqualTo(invalid);
        var node=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(child);node.remove("invalidation");
        assertThat(json.treeToValue(node,TaskRecord.class).invalidation()).isNull();
    }
    @Test void cycleMissingAndForeignDependenciesFailClosed(){
        var a=task("a",List.of("b"),List.of(),TaskStatus.WAITING_DEPENDENCY,TaskScope.PREPARE);
        task("b",List.of("a"),List.of(),TaskStatus.WAITING_DEPENDENCY,TaskScope.PREPARE);
        assertThat(TaskDependencies.invalidation(store,a,NOW)).isPresent();
        var missing=task("missing",List.of("absent"),List.of(),TaskStatus.WAITING_DEPENDENCY,TaskScope.PREPARE);
        assertThat(TaskDependencies.invalidation(store,missing,NOW).orElseThrow().code()).isEqualTo(TaskInvalidation.Code.DEPENDENCY_MISSING);
        var foreign=new TaskRecord("foreign","other","other",1,new TaskSpec("foreign","domain","private",null,null,null),"child",TaskStatus.COMPLETED,"private",List.of(),false,0,0,0);store.createTask(foreign);
        var wrong=task("wrong",List.of(foreign.id()),List.of(),TaskStatus.WAITING_DEPENDENCY,TaskScope.PREPARE);
        assertThat(TaskDependencies.invalidation(store,wrong,NOW).orElseThrow().code()).isEqualTo(TaskInvalidation.Code.DEPENDENCY_SCOPE);
    }
    @Test void lateCompletionCannotClearItsPersistedInvalidation(){
        var source=quote("source",NOW+1);var sink=Sinks.<TaskWorker.Result>one();
        var runtime=runtime((t,g,i,e)->sink.asMono());
        var spec=new TaskSpec("consumer","domain","consume",null,List.of(source.id()),TaskScope.PREPARE,List.of(input(source)));
        var subscription=runtime.submit("root",spec,budget).subscribe();time.incrementAndGet();runtime.list("root");
        sink.tryEmitValue(new TaskWorker.Result("late success",false,List.of(),null));
        var consumer=store.tasks("root").stream().filter(t->t.spec().key().equals("consumer")).findFirst().orElseThrow();
        assertThat(consumer.status()).isEqualTo(TaskStatus.STALE);assertThat(consumer.invalidation()).isNotNull();assertThat(consumer.reply()).doesNotContain("late success");subscription.dispose();
    }
    @Test void laterFailuresAddRepairRootsWithoutOverwritingTheFirstPersistedCause(){
        var a=quote("source-a",NOW+1);var b=quote("source-b",NOW+2);
        var first=input(a);var second=input(b);
        task("consumer",List.of(a.id(),b.id()),List.of(first,new InputBinding(b.id(),second.factId(),second.version(),"secondQuote")),TaskStatus.COMPLETED,TaskScope.PREPARE);
        time.incrementAndGet();var runtime=runtime();runtime.reviewPlan("root");var initial=store.task("consumer").orElseThrow().invalidation();
        assertThat(initial.rootTaskId()).isEqualTo(a.id());time.incrementAndGet();var report=runtime.reviewPlan("root");
        assertThat(report.sourceRepairs()).extracting(PlanReview.Repair::taskId).containsExactly(a.id(),b.id());
        assertThat(report.affectedTasks().get(0).blockedBy()).containsExactly(a.id(),b.id());
        assertThat(store.task("consumer").orElseThrow().invalidation()).isEqualTo(initial);
    }
    @Test void mismatchedVersionWithStillValidEvidenceSuggestsRebindingInsteadOfAnotherProviderCall(){
        var source=quote("source",NOW+1000);var old=input(source);
        task("consumer",List.of(source.id()),List.of(new InputBinding(source.id(),old.factId(),"old-version",old.alias())),TaskStatus.COMPLETED,TaskScope.PREPARE);
        var report=runtime().reviewPlan("root");
        assertThat(report.sourceRepairs()).singleElement().satisfies(r->assertThat(r.action()).isEqualTo(PlanReview.Action.REBIND_FACT));
    }
    @Test void newTaskWithAnAlreadyFailedDependencyStillCarriesAnActionableCause(){
        var failed=task("failed",List.of(),List.of(),TaskStatus.FAILED,TaskScope.PREPARE);
        var result=runtime().submit("root",new TaskSpec("new-consumer","domain","analyze",null,List.of(failed.id()),TaskScope.PREPARE),budget).block();
        assertThat(result.status()).isEqualTo(TaskStatus.STALE);assertThat(result.invalidation().code()).isEqualTo(TaskInvalidation.Code.DEPENDENCY_FAILED);
        assertThat(runtime().reviewPlan("root").sourceRepairs()).singleElement().satisfies(r->assertThat(r.action()).isEqualTo(PlanReview.Action.REQUIRES_REVIEW));
    }
    @Test void oneSourceWithMultipleBadBindingsRequiresRefreshRatherThanOnlyRebinding(){
        var running=task("source",List.of(),List.of(),TaskStatus.RUNNING,TaskScope.PREPARE);
        for(var key:List.of("first","second")){
            var observation=new FactObservation(key,key,"test","query","hash",NOW,"","",null);
            running=running.withObservation(observation,true,NOW);
            running=running.withObservation(observation.returned(running,key,new FactExtractor.Value("ride_quote",Map.of("value",key),key.equals("first")?NOW+1000:NOW+1),NOW),false,NOW);
        }
        var source=running.next(TaskStatus.COMPLETED,"done",List.of(),false,NOW);store.records.put(source.id(),source);
        var facts=TaskFacts.of(source);
        task("consumer",List.of(source.id()),List.of(new InputBinding(source.id(),facts.get(0).id(),"wrong-version","first"),
                new InputBinding(source.id(),facts.get(1).id(),facts.get(1).version(),"second")),TaskStatus.COMPLETED,TaskScope.PREPARE);
        time.incrementAndGet();
        assertThat(runtime().reviewPlan("root").sourceRepairs()).singleElement().satisfies(r->assertThat(r.action()).isEqualTo(PlanReview.Action.REFRESH_PREPARATION));
    }
    @Test void ordinaryGlobalCorrectionsStillInvalidateUnrelatedPendingApprovals(){
        var pending=task("pending",List.of(),List.of(),TaskStatus.AWAITING_APPROVAL,TaskScope.REQUEST_APPROVAL);
        var runtime=runtime();runtime.captureUser("root","修改预算");
        assertThat(runtime.require("root",pending.id()).status()).isEqualTo(TaskStatus.STALE);
        assertThat(runtime.reviewPlan("root").goalRevision()).isEqualTo(2);
    }
}
