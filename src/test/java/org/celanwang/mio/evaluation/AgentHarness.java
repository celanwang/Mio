package org.celanwang.mio.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.*;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.judge.jev.JevClient;
import org.celanwang.mio.memory.*;
import org.celanwang.mio.model.dto.ChatRequest;
import org.celanwang.mio.model.vo.ChatResponse;
import org.celanwang.mio.security.*;
import org.celanwang.mio.service.*;
import org.celanwang.mio.trace.TraceStore;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Exercises actual AgentScope state/permissions and production ChatService; only the model and I/O are fake. */
final class AgentHarness implements AutoCloseable {
    final Toolkit toolkit = new Toolkit();
    final PolicyEngine policy = new PolicyEngine("shadow");
    final TraceStore traces = new TraceStore((s,e) -> {});
    final TrustStore trust = mock(TrustStore.class);
    final ScenarioWorld world = new ScenarioWorld();
    final List<List<Msg>> modelInputs = new ArrayList<>();
    final Deque<List<ContentBlock>> script = new ArrayDeque<>();
    ReActAgent agent;
    ChatService service;
    private final JevClient jev;
    private final Model suppliedModel;
    private final boolean delimiters;
    private final String systemPrompt;
    String userText = "SYNTHETIC evaluation task";
    final String session = UUID.randomUUID().toString();

    AgentHarness(JevClient jev) { this(jev, null, true, ""); }
    AgentHarness(JevClient jev, Model model, boolean delimiters, String systemPrompt) {
        this.jev = jev; this.suppliedModel = model; this.delimiters = delimiters; this.systemPrompt = systemPrompt;
    }
    private void initialize() {
        if (agent != null) return;
        var model = mock(Model.class);
        when(model.getModelName()).thenReturn("scripted-fixture-v1");
        when(model.stream(anyList(), any(), any())).thenAnswer(inv -> Flux.defer(() -> {
            modelInputs.add(List.copyOf(inv.getArgument(0)));
            if (script.isEmpty()) return Flux.error(new AssertionError("Script exhausted; unexpected model turn"));
            return Flux.just(io.agentscope.core.model.ChatResponse.builder().content(script.removeFirst()).build());
        }));
        var builder = ReActAgent.builder().name("offline-eval").sysPrompt(systemPrompt)
                .model(suppliedModel == null ? model : suppliedModel).toolkit(toolkit)
                .stateStore(new InMemoryAgentStateStore()).permissionContext(policy.permissionContext())
                .generateOptions(io.agentscope.core.model.GenerateOptions.builder()
                        .temperature(Double.parseDouble(System.getProperty("mio.eval.temperature", "0"))).build())
                .maxIters(12);
        if (delimiters) builder.hook(new UntrustedContentHook());
        agent = builder.build();
        var memory = mock(MioLongTermMemory.class);
        when(memory.record(anyList(), anyString())).thenReturn(Mono.empty());
        @SuppressWarnings("unchecked") ObjectProvider<JevClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jev);
        service = new ChatService(agent, mock(WeiboMcpService.class), provider, .8,
                suppliedModel == null ? "scripted-fixture-v1" : suppliedModel.getModelName(), "mock-jev", traces, trust, policy, memory,
                mock(HabitDetector.class), new ObjectMapper());
    }
    AgentHarness() { this(null); }
    void addRide() throws Exception {
        var ride = mock(TencentRideService.class);
        when(ride.execute(nullable(String.class), anyMap())).thenAnswer(inv -> {
            Map<String,Object> request = inv.getArgument(1);
            String command = (String) request.get("command");
            @SuppressWarnings("unchecked") Map<String,Object> args = new LinkedHashMap<>((Map<String,Object>) request.getOrDefault("parameters", Map.of()));
            if (request.containsKey("stateVersion")) args.put("stateVersion", request.get("stateVersion"));
            String action = "create-order".equals(command) ? "ride-create" : "cancel-order".equals(command) ? "ride-cancel" : command;
            return new ObjectMapper().writeValueAsString(world.execute(action, args));
        });
        toolkit.registerAgentTool(new org.celanwang.mio.tool.TencentRideTool(ride));
        toolkit.registerAgentTool(new org.celanwang.mio.tool.TencentRideCreateOrderTool(ride));
    }
    void add(String name, boolean readOnly) { toolkit.registerAgentTool(world.tool(name, readOnly)); }
    void call(String name, Map<String, Object> input) {
        try {
            script.add(List.of(ToolUseBlock.builder().id(UUID.randomUUID().toString()).name(name).input(input)
                    .content(new ObjectMapper().writeValueAsString(input)).build()));
        } catch (Exception e) { throw new IllegalArgumentException(e); }
    }
    void say(String text) { script.add(List.of(TextBlock.builder().text(text).build())); }
    ChatResponse start() {
        initialize();
        var request = new ChatRequest(); request.setSessionId(session); request.setMessage(userText);
        return service.chat(request).block(Duration.ofSeconds(suppliedModel == null ? 10 : 60));
    }
    ChatResponse decide(ChatResponse pending, boolean approved) {
        var request = new ChatRequest(); request.setSessionId(session);
        request.setConfirmations(pending.getPendingConfirmations().stream()
                .map(c -> new ChatRequest.Confirmation(c.toolCallId(), approved)).toList());
        return service.chat(request).block(Duration.ofSeconds(suppliedModel == null ? 10 : 60));
    }
    @Override public void close() { if (agent != null) agent.close(); }
}
