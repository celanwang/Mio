package org.celanwang.mio.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.*;
import io.agentscope.extensions.judge.jev.JevClient;
import org.celanwang.mio.memory.*;
import org.celanwang.mio.model.dto.ChatRequest;
import org.celanwang.mio.security.PolicyEngine;
import org.celanwang.mio.trace.TraceStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import java.time.Duration;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ChatServiceConcurrencyTest {
    private static Msg reply() {
        return Msg.builder().role(MsgRole.ASSISTANT).content(TextBlock.builder().text("done").build()).build();
    }
    private static ChatRequest request() {
        var r = new ChatRequest(); r.setSessionId("same-session"); r.setMessage("next"); return r;
    }
    private static ChatService service(ReActAgent agent) {
        @SuppressWarnings("unchecked") ObjectProvider<JevClient> jev = mock(ObjectProvider.class);
        return new ChatService(agent, mock(WeiboMcpService.class), jev, .8, "fixture", "fixture",
                new TraceStore((s,e)->{}), mock(TrustStore.class), new PolicyEngine("shadow"),
                mock(MioLongTermMemory.class), mock(HabitDetector.class), new ObjectMapper());
    }

    @Test void immediateNextTurnAcquiresLockButOldFinalizerCannotReleaseItsOwnership() {
        var agent = mock(ReActAgent.class);
        var pending = Sinks.<Msg>one();
        AtomicInteger calls = new AtomicInteger();
        when(agent.call(anyList(), any())).thenAnswer(inv -> calls.incrementAndGet()==2 ? pending.asMono() : Mono.just(reply()));
        var service = service(agent);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean();
        var subscription = service.chat(request()).flatMap(first -> service.chat(request()))
                .subscribe(result -> completed.set(true), error::set);
        assertThat(error.get()).isNull();
        assertThat(calls.get()).isEqualTo(2);
        // The previous request's doFinally has run, while the second is still executing.
        assertThatThrownBy(() -> service.chat(request()).block(Duration.ofSeconds(2))).hasMessageContaining("409");
        assertThat(calls.get()).isEqualTo(2);
        pending.tryEmitValue(reply());
        assertThat(completed.get()).isTrue();
        assertThat(service.chat(request()).block(Duration.ofSeconds(2))).isNotNull();
        assertThat(calls.get()).isEqualTo(3);
        subscription.dispose();
    }

    @Test void cancellationAndErrorsReleaseOnlyTheirOwnRequest() {
        var agent = mock(ReActAgent.class);
        when(agent.call(anyList(), any())).thenReturn(Mono.never());
        var service = service(agent);
        var request = service.chat(request()).subscribe();
        assertThatThrownBy(() -> service.chat(request()).block(Duration.ofSeconds(2))).hasMessageContaining("409");
        request.dispose();
        when(agent.call(anyList(), any())).thenReturn(Mono.error(new IllegalStateException("offline")));
        assertThatThrownBy(() -> service.chat(request()).block(Duration.ofSeconds(2))).hasMessageContaining("offline");
        when(agent.call(anyList(), any())).thenReturn(Mono.just(reply()));
        assertThat(service.chat(request()).block(Duration.ofSeconds(2))).isNotNull();
    }
}
