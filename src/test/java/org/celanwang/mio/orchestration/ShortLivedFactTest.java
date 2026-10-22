package org.celanwang.mio.orchestration;

import com.fasterxml.jackson.databind.*;
import io.agentscope.core.message.*;
import io.agentscope.core.tool.*;
import org.celanwang.mio.security.PolicyEngine;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShortLivedFactTest {
    static final long NOW=1_800_000_000_000L;
    final ObjectMapper json=new ObjectMapper();
    final InMemoryCoordinationStore store=new InMemoryCoordinationStore();
    final GoalRecord goal=new GoalRecord("g","root",1,"intent","intent",NOW,NOW);
    final PolicyEngine policy=new PolicyEngine("shadow");
    final AtomicLong time=new AtomicLong(NOW);
    final Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochMilli(time.get());}};
    final RunBudget budget=new RunBudget(new RunBudget.Limits(20,100,20,10000,10000,10000),clock);
    final TencentQuoteFactExtractor extractor=new TencentQuoteFactExtractor();
    ShortLivedFactTest(){store.createGoal(goal);}
    static String quote(long timestamp){return """
            {"exitCode":0,"error":null,"output":{"mode":"estimated","result":{"status":200,"body":{"code":0,"data":{
            "estimateKey":"estimate","distance":1800.5,"estimateTime":900,"product":[{"riderInfo":{"rideType":1,"riderDesc":"快车"},
            "priceEstimate":[{"priceEstimateKey":"private-key","estimatePrice":1800}]}]}}}},
            "stateVersion":"%s","quotedAt":%s,"units":{"distance":"m","estimateTime":"s","estimatePrice":"fen"},
            "trip":{"pickup":{"poiid":"p1","point_name":"起点北门","selected":true},"dropoff":{"poiid":"p2","point_name":"机场出发层","selected":true}}}
            """.formatted("a".repeat(64),java.math.BigDecimal.valueOf(timestamp,3).toPlainString());}
    TaskRecord task(){
        var task=new TaskRecord("source","root","g",1,new TaskSpec("source","domain","quote",null,null,TaskScope.PREPARE),"child","v1",TaskStatus.RUNNING,"",null,List.of(),false,List.of(),0,NOW,NOW);
        store.createTask(task);return task;
    }
    TaskExecution execution(TaskRecord task){return new TaskExecution(store,task,goal,budget,policy,clock,Map.of(),List.of(),List.of(extractor),null);}
    ToolUseBlock call(String command){return ToolUseBlock.builder().id(UUID.randomUUID().toString()).name("tencent_ride").input(Map.of("command",command,"parameters",Map.of())).build();}
    TaskFact observe(TaskExecution execution,String raw){
        var call=call("estimate-price");var pending=execution.observationStarting(call).orElseThrow();
        execution.observationReturned(pending,call,raw,"SUCCESS");return TaskFacts.of(store.task("source").orElseThrow()).get(0);
    }
    @Test void strictBridgeQuoteHasProviderBasedExpiryAndNoTransferableBookingKeys(){
        var value=extractor.extract("tencent_ride",Map.of("command","estimate-price"),quote(NOW-100_000),NOW).orElseThrow();
        assertThat(value.expiresAt()).isEqualTo(NOW+200_000);
        assertThat(value.type()).isEqualTo("ride_quote");assertThat(value.value()).containsEntry("durationSeconds","900");
        assertThat(value.value().toString()).contains("机场出发层","amountFen").doesNotContain("private-key","stateVersion","estimateKey");
        var fractional=quote(NOW).replace("1800000000.000","1800000000.000123");
        assertThat(extractor.extract("tencent_ride",Map.of("command","estimate-price"),fractional,NOW+1).orElseThrow().expiresAt()).isEqualTo(NOW+300_000);
    }
    @Test void errorsMalformedTypesDuplicatesAndExpiredQuotesNeverBecomeFacts()throws Exception{
        var mutations=List.of(
                quote(NOW-300000),quote(NOW+1),quote(NOW).replace("\"exitCode\":0","\"exitCode\":\"0\""),
                quote(NOW).replace("\"status\":200","\"status\":500"),quote(NOW).replace("\"code\":0","\"code\":false"),
                quote(NOW).replace("\"error\":null","\"error\":\"failed\""),quote(NOW).replace("\"estimatePrice\":1800","\"estimatePrice\":\"1800\""),
                quote(NOW).replace("\"estimatePrice\":1800","\"estimatePrice\":-1"),quote(NOW).replace("\"estimatePrice\":1800","\"estimatePrice\":1800.1"),
                quote(NOW).replace("\"estimatePrice\":\"fen\"","\"estimatePrice\":\"yuan\""),quote(NOW).replace("\"selected\":true","\"selected\":false"),
                quote(NOW).replace("\"rideType\":1","\"rideType\":\"1\""),quote(NOW).replace("\"exitCode\":0","\"exitCode\":1,\"exitCode\":0"),
                quote(NOW)+" {}",quote(NOW).replace("\"quotedAt\":1800000000.000","\"quotedAt\":1e30"));
        for(var raw:mutations)assertThat(extractor.extract("tencent_ride",Map.of("command","estimate-price"),raw,NOW)).as(raw).isEmpty();
        assertThat(extractor.extract("unregistered-tool",Map.of("command","estimate-price"),quote(NOW),NOW)).isEmpty();
    }
    @Test void refreshStartAndDraftChangesRemoveOldFactsEvenWhenTheCallFails(){
        var execution=execution(task());var first=observe(execution,quote(NOW));
        assertThat(first.sourceTask()).isEqualTo("source");assertThat(first.evidenceId()).isNotBlank();
        var refresh=call("estimate-price");var pending=execution.observationStarting(refresh).orElseThrow();
        assertThat(TaskFacts.of(store.task("source").orElseThrow())).isEmpty();
        execution.observationReturned(pending,refresh,"network failed","ERROR");
        assertThat(TaskFacts.of(store.task("source").orElseThrow())).isEmpty();
        var next=observe(execution,quote(NOW));assertThat(next.version()).isNotEqualTo(first.version());
        execution.observationStarting(call("poi-search"));
        assertThat(TaskFacts.of(store.task("source").orElseThrow())).isEmpty();
    }
    @Test void laterStartedRefreshWinsOverEarlierLateResponse(){
        var execution=execution(task());var first=call("estimate-price");var a=execution.observationStarting(first).orElseThrow();
        var second=call("estimate-price");var b=execution.observationStarting(second).orElseThrow();
        execution.observationReturned(b,second,"error","ERROR");execution.observationReturned(a,first,quote(NOW),"SUCCESS");
        assertThat(TaskFacts.of(store.task("source").orElseThrow())).isEmpty();
    }
    @Test void cancelledOrRevisedGoalsCannotPublishLateQuoteEvidence(){
        var original=task();var execution=execution(original);var call=call("estimate-price");var pending=execution.observationStarting(call).orElseThrow();
        var current=store.task(original.id()).orElseThrow();store.saveTask(current,current.next(TaskStatus.CANCELLED,"cancel",List.of(),false,NOW),"cancel");
        assertThatThrownBy(()->execution.observationReturned(pending,call,quote(NOW),"SUCCESS")).isInstanceOf(IllegalStateException.class);
        assertThat(TaskFacts.of(store.task(original.id()).orElseThrow())).isEmpty();
    }
    @Test void expiredBoundQuoteFencesToolsAndLateCompletion(){
        var execution=execution(task());var fact=observe(execution,quote(NOW-299999));
        var source=store.task("source").orElseThrow();store.saveTask(source,source.next(TaskStatus.COMPLETED,"done",List.of(),false,NOW),"done");
        var spec=new TaskSpec("consume","domain","analyze",null,List.of("source"),TaskScope.PREPARE,List.of(new InputBinding("source",fact.id(),fact.version(),"rideQuote")),"ride-quote-context");
        var cap=new AgentCapability("domain","domain","desc","skill","v1",List.of(),CapabilityInputContract.contextual());
        var lease=new AtomicReference<TaskExecution>();var sink=reactor.core.publisher.Sinks.<TaskWorker.Result>one();
        var runtime=new TaskRuntime(store,new AgentRegistry(List.of(cap)),(t,g,i,e)->{lease.set(e);assertThat(i.bindings()).hasSize(1);return sink.asMono();},policy,clock,16);
        var subscription=runtime.submit("root",spec,budget).subscribe();assertThat(lease.get()).isNotNull();
        time.incrementAndGet();assertThatThrownBy(()->lease.get().businessActive()).isInstanceOf(TaskFacts.InputUnavailable.class);
        sink.tryEmitValue(new TaskWorker.Result("late",false,List.of(),null));
        assertThat(store.tasks("root").stream().filter(t->t.spec().key().equals("consume")).findFirst().orElseThrow().status()).isEqualTo(TaskStatus.STALE);
        assertThat(TaskFacts.validAt(source,time.get())).isEmpty();subscription.dispose();
    }
    @Test void guardedRealToolBoundaryPersistsInvalidationBeforeInvocation(){
        var execution=execution(task());observe(execution,quote(NOW));var invocations=new AtomicInteger();
        var tool=new ToolBase(ToolBase.builder().name("tencent_ride").description("test").inputSchema(Map.of()).readOnly(false)){
            @Override public Mono<ToolResultBlock> callAsync(ToolCallParam p){
                assertThat(TaskFacts.of(store.task("source").orElseThrow())).isEmpty();invocations.incrementAndGet();return Mono.just(ToolResultBlock.text(quote(NOW)));
            }
        };
        var param=mock(ToolCallParam.class);var call=call("estimate-price");when(param.getToolUseBlock()).thenReturn(call);when(param.getInput()).thenReturn(call.getInput());
        new GuardedDomainTool(tool,execution,policy).callAsync(param).block();
        assertThat(invocations.get()).isEqualTo(1);assertThat(TaskFacts.of(store.task("source").orElseThrow())).hasSize(1);
        assertThat(store.events).contains("fact_observation_started","fact_observation_returned");
    }
    @Test void legacyJsonLoadsWithEmptyObservationsAndNewRecordsRoundTrip()throws Exception{
        var original=task();var object=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(original);object.remove("observations");((com.fasterxml.jackson.databind.node.ObjectNode)object.get("spec")).remove("inputContract");
        var legacy=json.treeToValue(object,TaskRecord.class);assertThat(legacy.observations()).isEmpty();assertThat(legacy.spec().inputContract()).isEqualTo("default");
        observe(execution(original),quote(NOW));var current=store.task(original.id()).orElseThrow();
        assertThat(json.readValue(json.writeValueAsString(current),TaskRecord.class)).isEqualTo(current);
        assertThat(current.next(TaskStatus.NEEDS_INPUT,"question",List.of(),false,NOW).observations()).isEqualTo(current.observations());
    }
}
