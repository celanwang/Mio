package org.celanwang.mio.security;

import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.Toolkit;
import org.celanwang.mio.memory.AutomationStore;
import org.celanwang.mio.trace.TraceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BoundaryTest {
    @ParameterizedTest @ValueSource(strings = {"localhost", "127.0.0.1", "127.10.1.2", "::1", "[::1]", "0:0:0:0:0:0:0:1"})
    void acceptsLoopback(String host) { assertThat(Loopback.isLoopback(host)).isTrue(); }

    @ParameterizedTest @ValueSource(strings = {"", "example.com", "localhost.evil", "192.168.1.2", "0.0.0.0", "256.0.0.1", "::", "[::2]"})
    void rejectsNonLoopbackWithoutDns(String host) { assertThat(Loopback.isLoopback(host)).isFalse(); }

    @Test void interceptorRequiresBothPeerAndHost() {
        var interceptor = new LocalOnlyInterceptor();
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.setServerName("evil.example");
        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), this))
                .hasMessageContaining("403");
        request.setServerName("localhost");
        request.setRemoteAddr("10.1.2.3");
        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), this))
                .hasMessageContaining("403");
    }

    @Test void registrationRejectsShadowingAndWrongCounts() {
        var toolkit = new Toolkit();
        var tool = mock(ToolBase.class);
        when(tool.getName()).thenReturn("query-order");
        toolkit.registerAgentTool(tool);
        var guard = new ToolRegistrationGuard();
        assertThatThrownBy(() -> guard.checkLocalTool(toolkit, tool, "remote"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> guard.verifyMcpRegistration(toolkit, Set.of("query-order"), 1, "remote"))
                .isInstanceOf(IllegalStateException.class);
        guard.verifyMcpRegistration(toolkit, Set.of(), 1, "remote");
    }

    @Test void killSwitchRevokesActiveRulesAndAudits() {
        var store = mock(AutomationStore.class);
        when(store.revokeAllActive()).thenReturn(2);
        var traces = new TraceStore((session, event) -> {});
        var engine = new PolicyEngine("shadow");
        var controller = new SecurityController(engine, store, traces);
        assertThat(controller.killSwitch(Map.of("enabled", true))).containsEntry("revokedAutomations", 2);
        assertThat(engine.isCircuitOpen()).isTrue();
        assertThat(traces.events("system")).hasSize(1);
        controller.killSwitch(Map.of("enabled", false));
        verify(store, times(1)).revokeAllActive();
    }
}
