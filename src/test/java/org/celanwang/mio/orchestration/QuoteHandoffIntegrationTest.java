package org.celanwang.mio.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.*;
import io.agentscope.extensions.judge.jev.JevClient;
import org.celanwang.mio.memory.*;
import org.celanwang.mio.security.PolicyEngine;
import org.celanwang.mio.service.*;
import org.celanwang.mio.tool.TencentRideTool;
import org.celanwang.mio.trace.TraceStore;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class QuoteHandoffIntegrationTest {
    @Test void actualDomainLoopsProduceAndConsumeATypedQuoteWithoutSharingBusinessState()throws Exception{
        var json=new ObjectMapper();var store=new InMemoryCoordinationStore();var states=new InMemoryAgentStateStore();var policy=new PolicyEngine("shadow");
        var ride=mock(TencentRideService.class);long quotedAt=System.currentTimeMillis()-1000;
        when(ride.execute(anyString(),anyMap())).thenReturn(ShortLivedFactTest.quote(quotedAt));
        var rideCap=new AgentCapability("ride-domain","出行","desc","ride","skill-v1",List.of(),CapabilityInputContract.contextual());
        var analysisCap=new AgentCapability("analysis-domain","分析","desc","analysis","skill-v1",List.of(),CapabilityInputContract.contextual());
        var rideScript=new ArrayDeque<List<ContentBlock>>();var analysisScript=new ArrayDeque<List<ContentBlock>>();var consumerMessages=new ArrayList<List<Msg>>();
        rideScript.add(turn(json,"tencent_ride",Map.of("command","estimate-price","parameters",Map.of())));
        rideScript.add(turn(json,"mio_finish_task",Map.of("text","报价准备完成")));
        analysisScript.add(turn(json,"mio_finish_task",Map.of("text","参考快照估算时间，未执行业务操作")));
        var rideModel=model(rideScript,new ArrayList<>());var analysisModel=model(analysisScript,consumerMessages);
        var dummy=new ToolBase(ToolBase.builder().name("analyze_context").description("read only").readOnly(true).inputSchema(Map.of())){};
        var definitions=List.of(new DomainDefinition(rideCap,"仅查询",()->List.of(new TencentRideTool(ride))),new DomainDefinition(analysisCap,"分析时间与预算",()->List.of(dummy)));
        var profiles=mock(ProfileStore.class);when(profiles.pages()).thenReturn(List.of());when(profiles.render(anyList())).thenReturn("");
        @SuppressWarnings("unchecked") ObjectProvider<JevClient> jev=mock(ObjectProvider.class);
        var factory=new DomainAgentFactory(definitions,c->c.id().equals(rideCap.id())?rideModel:analysisModel,states,null,policy,mock(WeiboMcpService.class),jev,.8,
                new TraceStore((s,e)->{}),mock(TrustStore.class),mock(MioLongTermMemory.class),mock(HabitDetector.class),profiles,json);
        var runtime=new TaskRuntime(store,new AgentRegistry(List.of(rideCap,analysisCap)),factory,policy,Clock.systemUTC(),16,List.of(),List.of(),List.of(new TencentQuoteFactExtractor()));
        runtime.captureUser("root","先询价，再按报价分析预算，不下单");
        var budget=new RunBudget(new RunBudget.Limits(30,50,10,100000,100000,20000),Clock.systemUTC());
        var source=runtime.submit("root",new TaskSpec("quote",rideCap.id(),"准备询价",null,null,TaskScope.PREPARE),budget).block(Duration.ofSeconds(10));
        assertThat(source.status()).isEqualTo(TaskStatus.COMPLETED);var fact=TaskSummary.of(source,new AgentRegistry(List.of(rideCap))).facts().get(0);
        var spec=new TaskSpec("analysis",analysisCap.id(),"分析交通预算与时间",null,List.of(source.id()),TaskScope.PREPARE,
                List.of(new InputBinding(source.id(),fact.id(),fact.version(),"rideQuote")),"ride-quote-context");
        var consumer=runtime.submit("root",spec,budget).block(Duration.ofSeconds(10));
        assertThat(consumer.status()).isEqualTo(TaskStatus.COMPLETED);assertThat(consumer.childSessionId()).isNotEqualTo(source.childSessionId());
        var userText=consumerMessages.get(0).stream().filter(m->m.getRole()==MsgRole.USER).map(Msg::getTextContent).reduce("",String::concat);
        assertThat(userText).contains("rideQuote","ride_quote","durationSeconds","机场出发层",fact.evidenceId(),fact.version()).doesNotContain("private-key","stateVersion");
        verify(ride,times(1)).execute(eq(source.childSessionId()),anyMap());verifyNoMoreInteractions(ride);
        assertThat(source.operations()).isEmpty();assertThat(consumer.operations()).isEmpty();
    }
    private List<ContentBlock> turn(ObjectMapper json,String tool,Map<String,Object> args)throws Exception{return List.of(ToolUseBlock.builder().id(UUID.randomUUID().toString()).name(tool).input(args).content(json.writeValueAsString(args)).build());}
    private Model model(Deque<List<ContentBlock>> script,List<List<Msg>> inputs){
        var model=mock(Model.class);when(model.getModelName()).thenReturn("fake-domain");
        when(model.stream(anyList(),any(),any())).thenAnswer(inv->Flux.defer(()->{
            inputs.add(List.copyOf(inv.getArgument(0)));if(script.isEmpty())return Flux.error(new IllegalStateException("script exhausted"));
            return Flux.just(ChatResponse.builder().content(script.removeFirst()).usage(ChatUsage.builder().inputTokens(5).outputTokens(3).build()).build());
        }));return model;
    }
}
