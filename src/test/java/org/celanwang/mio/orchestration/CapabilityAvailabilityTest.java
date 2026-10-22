package org.celanwang.mio.orchestration;

import org.celanwang.mio.security.PolicyEngine;
import org.celanwang.mio.model.dto.ChatRequest;
import org.celanwang.mio.model.vo.ToolConfirmation;
import io.agentscope.core.message.*;
import io.agentscope.core.tool.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import java.util.*;
import java.time.Clock;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class CapabilityAvailabilityTest {
    final AgentCapability cap=new AgentCapability("domain","领域","description","skill","1",List.of());
    final InMemoryCoordinationStore store=new InMemoryCoordinationStore();
    final AtomicReference<CapabilityAvailability> status=new AtomicReference<>(CapabilityAvailability.of(CapabilityAvailability.State.UNAVAILABLE,"DISABLED","已停用"));
    final RunBudget budget=new RunBudget(new RunBudget.Limits(20,20,20,10000,10000,10000),Clock.systemUTC());
    TaskSpec spec(String key){return new TaskSpec(key,"domain","goal",null,null,TaskScope.PREPARE);}
    AgentRegistry registry(){return new AgentRegistry(List.of(cap),Map.of(cap.id(),status::get));}
    TaskRuntime runtime(TaskWorker worker){var r=new TaskRuntime(store,registry(),worker,new PolicyEngine("shadow"),Clock.systemUTC(),16);r.captureUser("root","intent");return r;}
    @Test void discoveryShowsCurrentLocalStateAndNeverExposesCheckerFailures(){
        var registry=registry();assertThat(registry.discover().get(0).availability().state()).isEqualTo(CapabilityAvailability.State.UNAVAILABLE);
        status.set(CapabilityAvailability.of(CapabilityAvailability.State.UNKNOWN,"LOCAL_CONFIGURED","尚未在线验证"));
        assertThat(registry.discover().get(0).availability().state()).isEqualTo(CapabilityAvailability.State.UNKNOWN);
        var broken=new AgentRegistry(List.of(cap),Map.of("domain",()->{throw new IllegalStateException("SYNTHETIC_SECRET");}));
        assertThat(broken.status("domain").message()).doesNotContain("SYNTHETIC_SECRET");assertThat(broken.status("domain").state()).isEqualTo(CapabilityAvailability.State.UNAVAILABLE);
    }
    @Test void unavailableCapabilityCannotCreateTasksOrStartWorkers(){
        var starts=new AtomicInteger();var r=runtime((t,g,i,e)->{starts.incrementAndGet();return Mono.just(new TaskWorker.Result("done",false,List.of(),null));});
        assertThatThrownBy(()->r.submit("root",spec("a"),budget).block()).isInstanceOf(CapabilityAvailability.Unavailable.class);
        assertThat(store.tasks("root")).isEmpty();assertThat(starts.get()).isZero();
        status.set(CapabilityAvailability.of(CapabilityAvailability.State.UNKNOWN,"NOT_CHECKED","未知"));
        assertThat(r.submit("root",spec("a"),budget).block().status()).isEqualTo(TaskStatus.COMPLETED);assertThat(starts.get()).isEqualTo(1);
    }
    @Test void domainFactoryRechecksAvailabilityBeforeResolvingTheModel(){
        var f=new DomainFixture();f.availability.set(CapabilityAvailability.of(CapabilityAvailability.State.UNAVAILABLE,"DISABLED","已停用"));
        assertThat(f.runtime.submit("root",f.spec("blocked",TaskScope.PREPARE),f.budget).block().status()).isEqualTo(TaskStatus.FAILED);
        assertThat(f.modelCalls.get()).isZero();assertThat(f.reads.get()).isZero();assertThat(f.writes.get()).isZero();
    }
    @Test void changedAvailabilityBlocksApprovalButDoesNotBlockRejection(){
        status.set(CapabilityAvailability.of(CapabilityAvailability.State.UNKNOWN,"NOT_CHECKED","未知"));
        var starts=new AtomicInteger();var r=runtime((t,g,i,e)->{starts.incrementAndGet();return Mono.just(new TaskWorker.Result("confirm",false,List.of(new ToolConfirmation("call","create-order","desc",Map.of(),true,"hash")),null));});
        var task=r.submit("root",new TaskSpec("a","domain","goal",null,null,TaskScope.REQUEST_APPROVAL),budget).block();status.set(CapabilityAvailability.of(CapabilityAvailability.State.UNAVAILABLE,"DISABLED","已停用"));
        var id=task.approvals().get(0).id();
        assertThatThrownBy(()->r.approve("root",List.of(new ChatRequest.Confirmation(id,true)),budget).block()).isInstanceOf(CapabilityAvailability.Unavailable.class);
        assertThat(r.require("root",task.id()).approvals().get(0).decision()).isNull();
        assertThat(r.approve("root",List.of(new ChatRequest.Confirmation(id,false)),budget).block().get(0).status()).isEqualTo(TaskStatus.CANCELLED);assertThat(starts.get()).isEqualTo(1);
    }
    @Test void preparationStateRestrictsEvenReadToolsUntilPrepared(){
        var starts=new AtomicInteger();var r=runtime((t,g,i,e)->{
            var calls=new AtomicInteger();
            ToolBase source=new ToolBase(ToolBase.builder().name("query-meals").description("query").inputSchema(Map.of("type","object")).readOnly(true)){
                public Mono<ToolResultBlock> callAsync(ToolCallParam call){calls.incrementAndGet();return Mono.just(ToolResultBlock.text("data"));}
            };
            var guarded=new GuardedDomainTool(source,e,new PolicyEngine("shadow"),status::get);
            var call=org.mockito.Mockito.mock(ToolCallParam.class);org.mockito.Mockito.when(call.getToolUseBlock()).thenReturn(ToolUseBlock.builder().id("read").name("query-meals").input(Map.of()).build());org.mockito.Mockito.when(call.getInput()).thenReturn(Map.of());
            assertThatThrownBy(()->guarded.callAsync(call).block()).isInstanceOf(CapabilityAvailability.Unavailable.class);assertThat(calls.get()).isZero();
            status.set(CapabilityAvailability.of(CapabilityAvailability.State.UNKNOWN,"TOOLS_LOADED","未验证"));guarded.callAsync(call).block();assertThat(calls.get()).isEqualTo(1);
            status.set(CapabilityAvailability.of(CapabilityAvailability.State.UNAVAILABLE,"DISABLED","已停用"));assertThatThrownBy(()->guarded.callAsync(call).block()).isInstanceOf(CapabilityAvailability.Unavailable.class);
            starts.incrementAndGet();return Mono.just(new TaskWorker.Result("done",false,List.of(),null));
        });
        status.set(new CapabilityAvailability(CapabilityAvailability.State.PREPARATION_REQUIRED,"PREPARE","先准备",Set.of("prepare"),0));
        r.submit("root",spec("a"),budget).block();assertThat(starts.get()).isEqualTo(1);
    }
}
