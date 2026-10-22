package org.celanwang.mio.orchestration;

import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.*;
import java.time.Clock;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PersistentBudgetTest {
    final InMemoryGoalBudgetStore store=new InMemoryGoalBudgetStore();
    RunBudget run(int models,long tokens){
        var run=new RunBudget(new RunBudget.Limits(10,10,10,1000,10000,10000),Clock.systemUTC());
        run.bind(store,"goal",new GoalBudgetStore.Limits(models,10,10,tokens));return run;
    }
    Model source(Flux<ChatResponse> responses){var model=mock(Model.class);when(model.stream(anyList(),any(),any())).thenReturn(responses);return model;}
    ChatResponse usage(int input,int output){return ChatResponse.builder().content(List.of()).usage(ChatUsage.builder().inputTokens(input).outputTokens(output).build()).build();}
    @Test void newForegroundRequestDoesNotResetGoalModelQuota(){
        var model=source(Flux.just(usage(5,3)));new BudgetedModel(model,run(1,100)).stream(List.of(new UserMessage("first")),null,null).blockLast();
        assertThatThrownBy(()->new BudgetedModel(model,run(999,999)).stream(List.of(new UserMessage("second")),null,null).blockLast()).isInstanceOf(RunBudget.BudgetExceeded.class);
        verify(model,times(1)).stream(anyList(),any(),any());var s=store.find("goal").orElseThrow();assertThat(s.knownTokens()).isEqualTo(8);assertThat(s.usageComplete()).isTrue();
    }
    @Test void missingUsageAndCancellationRemainVisibleAcrossRequests(){
        new BudgetedModel(source(Flux.just(ChatResponse.builder().content(List.of()).build())),run(5,100)).stream(List.of(),null,null).blockLast();
        var subscription=new BudgetedModel(source(Flux.never()),run(5,100)).stream(List.of(),null,null).subscribe();subscription.dispose();
        var s=store.find("goal").orElseThrow();assertThat(s.models()).isEqualTo(2);assertThat(s.unknownUsage()).isEqualTo(1);assertThat(s.pendingUsage()).isEqualTo(1);assertThat(s.usageComplete()).isFalse();
    }
    @Test void partialStreamFailureChargesObservedUsageWithoutClaimingCompleteness(){
        var model=source(Flux.concat(Flux.just(usage(5,2)),Flux.error(new IllegalStateException("network"))));
        assertThatThrownBy(()->new BudgetedModel(model,run(5,100)).stream(List.of(),null,null).blockLast()).hasMessage("network");
        var s=store.find("goal").orElseThrow();assertThat(s.knownTokens()).isEqualTo(7);assertThat(s.unknownUsage()).isEqualTo(1);
    }
    @Test void cumulativeUsageIsChargedOnceAndExceededTokensNeverExposeToolCalls(){
        var tool=ToolUseBlock.builder().id("call").name("create-order").input(Map.of()).build();
        var model=source(Flux.just(usage(5,1),ChatResponse.builder().content(List.of(tool)).usage(ChatUsage.builder().inputTokens(5).outputTokens(4).build()).build()));
        assertThatThrownBy(()->new BudgetedModel(model,run(5,8)).stream(List.of(),null,null).collectList().block()).isInstanceOf(RunBudget.BudgetExceeded.class);
        assertThat(store.find("goal").orElseThrow().knownTokens()).isEqualTo(9);
        assertThatThrownBy(()->new BudgetedModel(model,run(5,1000)).stream(List.of(),null,null).blockLast()).isInstanceOf(RunBudget.BudgetExceeded.class);
        verify(model,times(1)).stream(anyList(),any(),any());
    }
    @Test void negativeUsageStopsExecutionAndReviewCallsStayUnknown(){
        assertThatThrownBy(()->new BudgetedModel(source(Flux.just(usage(-1,3))),run(5,100)).stream(List.of(),null,null).blockLast()).isInstanceOf(IllegalStateException.class);
        run(5,100).model(0);var s=store.find("goal").orElseThrow();assertThat(s.models()).isEqualTo(2);assertThat(s.unknownUsage()).isEqualTo(2);
    }
    @Test void mainServiceSharesGoalQuotaAcrossTurnsAndReturnsUsageMetadata(){
        var f=new DomainFixture();var script=new SupervisorIntegrationTest.Script();script.say("one");script.say("two");script.say("must not call");
        var registry=new AgentRegistry(List.of(new AgentCapability("arbitrary-domain","领域","d","s","1",List.of())));
        var factory=new SupervisorFactory(script.model,f.states,registry,f.runtime,new com.fasterxml.jackson.databind.ObjectMapper());
        java.util.function.Supplier<MultiAgentChatService> service=()->new MultiAgentChatService(factory,f.runtime,registry,
                new RunBudget.Limits(10,10,10,1000,10000,10000),Clock.systemUTC(),new org.celanwang.mio.trace.TraceStore((s,e)->{}),null,null,store,new GoalBudgetStore.Limits(2,10,10,100));
        var request=new org.celanwang.mio.model.dto.ChatRequest();request.setSessionId("root");request.setMessage("hello");
        var first=service.get().chat(request).block();var second=service.get().chat(request).block();
        assertThat(first.getBudget().models()).isEqualTo(1);assertThat(second.getBudget().models()).isEqualTo(2);assertThat(second.getBudget().usageComplete()).isFalse();assertThat(second.getBudget().historyUnknown()).isTrue();
        assertThatThrownBy(()->service.get().chat(request).block()).isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(429));
        assertThat(script.catalogs).hasSize(2);
    }
    @Test void quotaFailureDoesNotConsumeTheFinalHumanApproval(){
        var f=new DomainFixture();f.call("create-order",Map.of());var task=f.runtime.submit("root",f.spec("q",TaskScope.REQUEST_APPROVAL),f.budget).block();
        var limited=run(5,100);store.states.put("goal",new GoalBudgetStore.Snapshot("goal",new GoalBudgetStore.Limits(5,10,1,100),0,0,1,0,0,0,1));
        assertThatThrownBy(()->f.runtime.approve("root",List.of(new org.celanwang.mio.model.dto.ChatRequest.Confirmation(task.approvals().get(0).id(),true)),limited).block()).isInstanceOf(RunBudget.BudgetExceeded.class);
        assertThat(f.runtime.require("root",task.id()).approvals().get(0).decision()).isNull();assertThat(f.writes.get()).isZero();
    }
    @Test void nonFinalUsageIsOnlyALowerBoundAndNewGoalCoverageIsExplicit(){
        var model=source(Flux.just(usage(5,2),ChatResponse.builder().content(List.of()).build()));
        new BudgetedModel(model,run(5,100)).stream(List.of(),null,null).blockLast();
        assertThat(store.find("goal").orElseThrow().unknownUsage()).isEqualTo(1);assertThat(store.find("goal").orElseThrow().knownTokens()).isEqualTo(7);
        assertThat(store.find("goal").orElseThrow().historyUnknown()).isFalse();
        store.open("legacy",new GoalBudgetStore.Limits(5,5,5,100),true);String call=store.reserve("legacy",GoalBudgetStore.Kind.MODEL);store.settle("legacy",call,0L,true);
        assertThat(store.open("legacy",new GoalBudgetStore.Limits(99,99,99,999),false).usageComplete()).isFalse();
    }
    @Test void newlyCreatedGoalKeepsCompleteCoverageAcrossLaterUserTurns(){
        var f=new DomainFixture();f.store.goals.clear();var script=new SupervisorIntegrationTest.Script();script.say("one");script.say("two");
        var registry=new AgentRegistry(List.of(new AgentCapability("arbitrary-domain","领域","d","s","1",List.of())));
        var service=new MultiAgentChatService(new SupervisorFactory(script.model,f.states,registry,f.runtime,new com.fasterxml.jackson.databind.ObjectMapper()),f.runtime,registry,
                new RunBudget.Limits(10,10,10,1000,10000,10000),Clock.systemUTC(),new org.celanwang.mio.trace.TraceStore((s,e)->{}),null,null,store,new GoalBudgetStore.Limits(5,10,10,100));
        var request=new org.celanwang.mio.model.dto.ChatRequest();request.setSessionId("root");request.setMessage("hello");
        assertThat(service.chat(request).block().getBudget().usageComplete()).isTrue();
        var next=service.chat(request).block().getBudget();assertThat(next.models()).isEqualTo(2);assertThat(next.historyUnknown()).isFalse();assertThat(next.usageComplete()).isTrue();
    }
    @Test void userCanRejectAllPendingCallsWithoutExecutionQuota(){
        var f=new DomainFixture();f.call("create-order",Map.of());var task=f.runtime.submit("root",f.spec("q",TaskScope.REQUEST_APPROVAL),f.budget).block();
        int modelCalls=f.modelCalls.get();var limited=run(5,100);
        store.states.put("goal",new GoalBudgetStore.Snapshot("goal",new GoalBudgetStore.Limits(5,10,1,100),0,0,1,0,0,0,1));
        var result=f.runtime.approve("root",List.of(new org.celanwang.mio.model.dto.ChatRequest.Confirmation(task.approvals().get(0).id(),false)),limited).block().get(0);
        assertThat(result.status()).isEqualTo(TaskStatus.CANCELLED);assertThat(result.approvals().get(0).decision()).isFalse();
        assertThat(f.modelCalls.get()).isEqualTo(modelCalls);assertThat(f.writes.get()).isZero();
    }
}
