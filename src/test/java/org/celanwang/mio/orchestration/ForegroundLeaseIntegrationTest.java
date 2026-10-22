package org.celanwang.mio.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.*;
import io.agentscope.core.message.TextBlock;
import org.celanwang.mio.model.dto.ChatRequest;
import org.celanwang.mio.trace.TraceStore;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ForegroundLeaseIntegrationTest {
    final DomainFixture fixture=new DomainFixture();
    final InMemoryExecutionLeaseStore leases=new InMemoryExecutionLeaseStore(Clock.systemUTC());
    final AgentRegistry registry=new AgentRegistry(List.of(new AgentCapability("arbitrary-domain","领域","desc","s","1",List.of())));
    final Model model=mock(Model.class);final CountDownLatch entered=new CountDownLatch(1);final Sinks.One<io.agentscope.core.model.ChatResponse> response=Sinks.one();
    ForegroundLeaseIntegrationTest(){when(model.getModelName()).thenReturn("fake-root");when(model.stream(anyList(),any(),any())).thenAnswer(inv->Flux.defer(()->{entered.countDown();return response.asMono().flux();}));}
    MultiAgentChatService service(){return new MultiAgentChatService(new SupervisorFactory(model,fixture.states,registry,fixture.runtime,new ObjectMapper()),fixture.runtime,registry,
            new RunBudget.Limits(20,20,20,10000,10000,10000),Clock.systemUTC(),new TraceStore((s,e)->{}),null,null,null,null,leases);}
    ChatRequest request(String session){var request=new ChatRequest();request.setSessionId(session);request.setMessage("普通聊天");return request;}
    @Test void independentEntryInstancesCannotOverlapAndTheNextRunRestoresTheCheckpoint()throws Exception{
        var first=service().chat(request("chat")).toFuture();assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(()->service().chat(request("chat")).block(Duration.ofSeconds(5))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("409");
        assertThat(fixture.runtime.goal("chat").revision()).isEqualTo(1);
        response.tryEmitValue(io.agentscope.core.model.ChatResponse.builder().content(List.of(TextBlock.builder().text("response").build())).usage(ChatUsage.builder().inputTokens(5).outputTokens(3).build()).build());
        assertThat(first.get(5,TimeUnit.SECONDS).getReply()).isEqualTo("response");
        var second=service().chat(request("chat")).block(Duration.ofSeconds(5));assertThat(second.getReply()).isEqualTo("response");
        assertThat(fixture.runtime.goal("chat").revision()).isEqualTo(2);
        var state=fixture.states.get(null,"chat:supervisor","agent_state",io.agentscope.core.state.AgentState.class).orElseThrow();assertThat(state.getContext()).hasSizeGreaterThanOrEqualTo(4);
    }
    @Test void cancelledHttpSubscriptionReleasesItsLeaseWithoutWaitingForTheModel()throws Exception{
        var subscription=service().chat(request("cancel")).subscribe();assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();subscription.dispose();
        var token=leases.acquire("cancel",1000);assertThat(leases.valid(token)).isTrue();leases.release(token);
    }
    @Test void validationFailuresReleaseTheLeaseWithoutChangingUserIntent(){
        var request=request("invalid");request.setResumeTaskId("task");
        assertThatThrownBy(()->service().chat(request).block(Duration.ofSeconds(5))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("400");
        assertThat(fixture.runtime.findGoal("invalid")).isEmpty();var next=leases.acquire("invalid",1000);assertThat(leases.valid(next)).isTrue();leases.release(next);
        verify(model,never()).stream(anyList(),any(),any());
    }
}
