package org.celanwang.mio.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.*;
import io.agentscope.core.state.*;
import org.celanwang.mio.security.PolicyEngine;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class ExecutionLeaseTest {
    final AtomicLong time=new AtomicLong(1000);
    final Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochMilli(time.get());}};
    final InMemoryExecutionLeaseStore leases=new InMemoryExecutionLeaseStore(clock);
    final InMemoryCoordinationStore store=new InMemoryCoordinationStore();
    final AgentRegistry registry=new AgentRegistry(List.of(new AgentCapability("domain","领域","desc","s","v1",List.of())));
    final PolicyEngine policy=new PolicyEngine("shadow");
    ExecutionLeaseTest(){store.createGoal(new GoalRecord("g","root",1,"intent","intent",1000,1000));}
    RunBudget budget(ExecutionLeaseStore.Token token){var budget=new RunBudget(new RunBudget.Limits(20,20,20,10000,10000,10000),clock);budget.bindLease(leases,token);return budget;}
    TaskRuntime runtime(TaskWorker worker){return new TaskRuntime(store,registry,worker,policy,clock,16,List.of(),List.of(),List.of(),leases);}
    TaskSpec spec(){return new TaskSpec("task","domain","prepare",null,null,TaskScope.PREPARE);}
    @Test void anotherRuntimeDoesNotInterruptLiveWorkAndNewGenerationDoesNotHideOldInterruptedWork(){
        var token=leases.acquire("root",100);var budget=budget(token);var sink=Sinks.<TaskWorker.Result>one();var errors=new AtomicReference<Throwable>();
        var first=runtime((t,g,i,e)->sink.asMono());var other=runtime((t,g,i,e)->Mono.error(new AssertionError("must not execute")));
        var subscription=first.submit("root",spec(),budget).subscribe(v->{},errors::set);
        var running=other.list("root").get(0);assertThat(running.status()).isEqualTo(TaskStatus.RUNNING);assertThat(running.executionLease()).isEqualTo(token);
        time.set(1100);var next=leases.acquire("root",100);assertThat(next.generation()).isGreaterThan(token.generation());
        assertThat(other.list("root").get(0).status()).isEqualTo(TaskStatus.FAILED);
        sink.tryEmitValue(new TaskWorker.Result("old success",false,List.of(),null));assertThat(errors.get()).isInstanceOf(ExecutionLeaseStore.LostLease.class);
        assertThat(store.task(running.id()).orElseThrow().reply()).doesNotContain("old success");assertThat(leases.valid(next)).isTrue();subscription.dispose();
    }
    @Test void scopedCheckpointRejectsStaleAndCrossSessionWrites(){
        var old=leases.acquire("root",100);var oldBudget=budget(old);var states=new InMemoryAgentStateStore();
        var scoped=FencedAgentStateStore.scope(states,oldBudget,"root:supervisor");
        scoped.save(null,"root:supervisor","agent_state",AgentState.builder().sessionId("root:supervisor").context(List.of(new UserMessage("old"))).build());
        assertThatThrownBy(()->scoped.get(null,"other","agent_state",AgentState.class)).isInstanceOf(IllegalArgumentException.class);
        time.set(1100);var next=leases.acquire("root",100);var current=FencedAgentStateStore.scope(states,budget(next),"root:supervisor");
        current.save(null,"root:supervisor","agent_state",AgentState.builder().sessionId("root:supervisor").context(List.of(new UserMessage("new"))).build());
        assertThatThrownBy(()->scoped.save(null,"root:supervisor","agent_state",AgentState.builder().build())).isInstanceOf(ExecutionLeaseStore.LostLease.class);
        assertThat(current.get(null,"root:supervisor","agent_state",AgentState.class).orElseThrow().getContext().get(0).getTextContent()).isEqualTo("new");
        leases.release(old);assertThat(leases.valid(next)).isTrue();
        assertThatThrownBy(()->current.delete(null,"root:supervisor")).isInstanceOf(IllegalStateException.class);
    }
    @Test void expiryFencesBudgetReservationsAndBusinessLease(){
        var token=leases.acquire("root",100);var budget=budget(token);var execution=new AtomicReference<TaskExecution>();var sink=Sinks.<TaskWorker.Result>one();
        var subscription=runtime((t,g,i,e)->{execution.set(e);return sink.asMono();}).submit("root",spec(),budget).subscribe();
        time.set(1100);
        assertThatThrownBy(budget::tool).isInstanceOf(ExecutionLeaseStore.LostLease.class);
        assertThatThrownBy(()->budget.beginModel(1)).isInstanceOf(ExecutionLeaseStore.LostLease.class);
        assertThatThrownBy(()->execution.get().businessActive()).isInstanceOf(ExecutionLeaseStore.LostLease.class);
        assertThat(budget.usage().toolCalls()).isZero();subscription.dispose();
    }
    @Test void productionRuntimeRequiresALeaseBeforeCreatingAnyTask(){
        var raw=new RunBudget(new RunBudget.Limits(10,10,10,1000,10000,1000),clock);
        assertThatThrownBy(()->runtime((t,g,i,e)->Mono.never()).submit("root",spec(),raw).block()).isInstanceOf(IllegalStateException.class).hasMessageContaining("租约");
        assertThat(store.tasks("root")).isEmpty();
    }
    @Test void aLeaseCannotBeReusedForAnotherConversation(){
        var token=leases.acquire("root",1000);var budget=budget(token);
        store.createGoal(new GoalRecord("other","other",1,"intent","intent",1000,1000));
        assertThatThrownBy(()->runtime((t,g,i,e)->Mono.never()).submit("other",spec(),budget).block()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("当前会话");
        assertThat(store.tasks("other")).isEmpty();
    }
    @Test void abandonedSubmittedTaskBecomesUnknownAndStillAcceptsTrustedLateEvidence()throws Exception{
        var token=leases.acquire("root",100);var budget=budget(token);var execution=new AtomicReference<TaskExecution>();var sink=Sinks.<TaskWorker.Result>one();
        var errors=new AtomicReference<Throwable>();var runtime=runtime((t,g,i,e)->{execution.set(e);return sink.asMono();});
        var subscription=runtime.submit("root",spec(),budget).subscribe(v->{},errors::set);var running=store.tasks("root").get(0);
        var op=new OperationRecord("op","call","write","hash",1000,"SUBMITTING","");store.startOperation(running,op,1000);
        time.set(1100);var recovered=runtime.list("root").get(0);assertThat(recovered.status()).isEqualTo(TaskStatus.UNKNOWN);
        execution.get().effectReturned(op.id(),"late raw receipt");assertThat(store.task(running.id()).orElseThrow().operations().get(0).receipts()).hasSize(1);
        assertThat(store.task(running.id()).orElseThrow().status()).isEqualTo(TaskStatus.UNKNOWN);
        sink.tryEmitValue(new TaskWorker.Result("late complete",false,List.of(),null));assertThat(errors.get()).isInstanceOf(ExecutionLeaseStore.LostLease.class);
        var json=new ObjectMapper();var restored=json.readValue(json.writeValueAsString(store.task(running.id()).orElseThrow()),TaskRecord.class);assertThat(restored.executionLease()).isEqualTo(token);
        var node=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(running);node.remove("executionLease");assertThat(json.treeToValue(node,TaskRecord.class).executionLease()).isNull();subscription.dispose();
    }
}
