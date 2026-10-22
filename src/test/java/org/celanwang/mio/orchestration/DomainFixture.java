package org.celanwang.mio.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.*;
import io.agentscope.extensions.judge.jev.JevClient;
import org.celanwang.mio.memory.*;
import org.celanwang.mio.security.PolicyEngine;
import org.celanwang.mio.service.WeiboMcpService;
import org.celanwang.mio.trace.TraceStore;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

final class DomainFixture {
    final InMemoryCoordinationStore store=new InMemoryCoordinationStore();
    final InMemoryAgentStateStore states=new InMemoryAgentStateStore();
    final PolicyEngine policy=new PolicyEngine("shadow");
    final MioLongTermMemory memory=mock(MioLongTermMemory.class);
    final AtomicInteger reads=new AtomicInteger(),writes=new AtomicInteger(),modelCalls=new AtomicInteger();
    final AtomicReference<String> toolDescription=new AtomicReference<>("contract-v1");
    final AtomicBoolean declaredReadOnly=new AtomicBoolean();
    final AtomicReference<CapabilityAvailability> availability=new AtomicReference<>(CapabilityAvailability.of(CapabilityAvailability.State.UNKNOWN,"NOT_CHECKED","未在线验证"));
    final Deque<List<ContentBlock>> script=new ArrayDeque<>();
    final List<List<Msg>> inputs=new ArrayList<>();
    final Model model=mock(Model.class);
    final TaskRuntime runtime;
    final AgentRegistry registry;
    final RunBudget budget=new RunBudget(new RunBudget.Limits(50,100,30,100000,100000,120000),Clock.systemUTC());
    DomainFixture(){
        this(List.of());
    }
    DomainFixture(List<OperationVerifier> verifiers){
        this(verifiers,List.of(),List.of());
    }
    DomainFixture(List<OperationVerifier> verifiers,List<CapabilityOutputContract> outputs,List<FactExtractor> extractors){
        when(model.getModelName()).thenReturn("fake-domain");
        when(model.stream(anyList(),any(),any())).thenAnswer(inv->Flux.defer(()->{
            modelCalls.incrementAndGet();inputs.add(List.copyOf(inv.getArgument(0)));
            if(script.isEmpty())return Flux.error(new IllegalStateException("fixture exhausted"));
            return Flux.just(ChatResponse.builder().content(script.removeFirst()).usage(ChatUsage.builder().inputTokens(5).outputTokens(3).build()).build());
        }));
        @SuppressWarnings("unchecked") ObjectProvider<JevClient> jev=mock(ObjectProvider.class);
        var profiles=mock(ProfileStore.class);when(profiles.pages()).thenReturn(List.of());when(profiles.render(anyList())).thenReturn("");
        var capability=new AgentCapability("arbitrary-domain","领域测试","按目标处理任务","test-skill","1",List.of(),List.of(),outputs);
        registry=new AgentRegistry(List.of(capability));
        var definition=new DomainDefinition(capability,"测试领域",()->List.of(tool("query-meals",true,reads),tool("create-order",false,writes)),availability::get);
        var factory=new DomainAgentFactory(List.of(definition),c->model,states,null,policy,mock(WeiboMcpService.class),jev,.8,
                new TraceStore((s,e)->{}),mock(TrustStore.class),memory,mock(HabitDetector.class),profiles,new ObjectMapper());
        runtime=new TaskRuntime(store,registry,factory,policy,Clock.systemUTC(),16,verifiers,List.of(),extractors);
        runtime.captureUser("root","只按我的明确需求执行，写操作逐次确认");
    }
    private ToolBase tool(String name,boolean read,AtomicInteger counter){
        return new ToolBase(ToolBase.builder().name(name).description(toolDescription.get())
                .inputSchema(Map.of("type","object","properties",Map.of())).readOnly(read||declaredReadOnly.get())){
            @Override public Mono<ToolResultBlock> callAsync(ToolCallParam call){counter.incrementAndGet();return Mono.just(ToolResultBlock.text("synthetic-receipt"));}
        };
    }
    void call(String name,Map<String,Object> args){
        try{script.add(List.of(ToolUseBlock.builder().id(UUID.randomUUID().toString()).name(name).input(args).content(new ObjectMapper().writeValueAsString(args)).build()));}
        catch(Exception e){throw new IllegalArgumentException(e);}
    }
    TaskSpec spec(String key,TaskScope scope){return new TaskSpec(key,"arbitrary-domain","完成分派任务",List.of(),List.of(),scope);}
}
