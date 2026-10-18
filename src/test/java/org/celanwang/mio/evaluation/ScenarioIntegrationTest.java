package org.celanwang.mio.evaluation;

import io.agentscope.extensions.judge.jev.*;
import org.celanwang.mio.service.TencentRideService;
import org.celanwang.mio.tool.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ScenarioIntegrationTest {
    static final Map<String, Object> MEAL = Map.of("storeCode", "SYNTHETIC-S1", "productCode", "SYNTHETIC-P1", "quantity", 1);

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void mcdOrderCannotExecuteBeforeCurrentHumanDecision(boolean approved) {
        try (var h = new AgentHarness()) {
            h.add("query-meals", true); h.add("calculate-price", true); h.add("create-order", false);
            h.call("query-meals", Map.of()); h.call("calculate-price", MEAL); h.call("create-order", MEAL); h.say("done");
            var pending = h.start();
            assertThat(h.world.orders).isEmpty();
            assertThat(pending.getPendingConfirmations()).hasSize(1);
            assertThat(pending.getPendingConfirmations().get(0).firstSeen()).isTrue();
            h.decide(pending, approved);
            assertThat(h.world.orders).hasSize(approved ? 1 : 0);
            if (approved) {
                assertThat(h.world.state).containsEntry("order", MEAL).containsEntry("amountFen", 2500);
                assertThat(h.world.actions).containsExactly("query-meals", "calculate-price", "create-order");
            }
            verify(h.trust).record("create-order", MEAL, approved);
            assertThat(h.traces.events(h.session)).anyMatch(e -> e.type().equals("confirm"))
                    .anyMatch(e -> e.type().equals("policy_decision"));
            assertThat(h.modelInputs.stream().flatMap(List::stream)
                    .flatMap(m -> m.getContentBlocks(io.agentscope.core.message.ToolResultBlock.class).stream())
                    .flatMap(r -> r.getOutput().stream())
                    .filter(b -> b instanceof io.agentscope.core.message.TextBlock)
                    .map(b -> ((io.agentscope.core.message.TextBlock)b).getText()).toList())
                    .anyMatch(s -> s != null && s.contains("<tool_data"));
        }
    }

    @Test void approvalDoesNotGrantFutureCallsAndPlainMessagesCannotResumePending() {
        try (var h = new AgentHarness()) {
            h.add("unseen_write", false);
            h.call("unseen_write", Map.of("n", 1)); h.call("unseen_write", Map.of("n", 2)); h.say("done");
            var first = h.start();
            assertThat(h.start().getPendingConfirmations()).hasSize(1);
            assertThat(h.world.actions).isEmpty();
            var second = h.decide(first, true);
            assertThat(second.getPendingConfirmations()).hasSize(1);
            assertThat(h.world.actions).containsExactly("unseen_write");
            h.decide(second, false);
            assertThat(h.world.actions).containsExactly("unseen_write");
            assertThatThrownBy(() -> h.decide(first, true)).hasMessageContaining("409");
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void jevOnlyAutoApprovesReversibleWritesAndKillSwitchStopsIt(boolean killSwitch) {
        var jev = mock(JevClient.class);
        when(jev.systemOne(any())).thenReturn(Mono.just(new SystemOneResult("mock", Map.of("consistent", new NoulAnswer(.99)), null)));
        try (var h = new AgentHarness(jev)) {
            h.policy.setCircuitOpen(killSwitch);
            h.add("auto-bind-coupons", false); h.call("auto-bind-coupons", Map.of()); h.say("done");
            var response = h.start();
            assertThat(h.world.state.containsKey("couponClaimed")).isEqualTo(!killSwitch);
            assertThat(response.getPendingConfirmations()).hasSize(killSwitch ? 1 : 0);
            if (killSwitch) verifyNoInteractions(jev);
            else verify(jev).systemOne(any());
            verify(h.trust, never()).record(anyString(), anyMap(), anyBoolean());
        }
    }

    @Test void jevFailureAndLowConfidenceKeepHumanFallbackAndMismatchSignal() {
        for (boolean failure : List.of(false, true)) {
            var jev = mock(JevClient.class);
            when(jev.systemOne(any())).thenReturn(failure ? Mono.error(new IllegalStateException("offline"))
                    : Mono.just(new SystemOneResult("mock", Map.of("consistent", new NoulAnswer(.79)), null)));
            try (var h = new AgentHarness(jev)) {
                h.add("auto-bind-coupons", false); h.call("auto-bind-coupons", Map.of()); h.say("done");
                var response = h.start();
                assertThat(h.world.actions).isEmpty();
                assertThat(response.getPendingConfirmations()).hasSize(1);
                h.decide(response, true);
                assertThat(h.world.state).containsEntry("couponClaimed", true);
                if (!failure) assertThat(h.traces.events(h.session)).anyMatch(e -> e.type().equals("jev_mismatch"));
            }
        }
    }

    @Test void irreversibleAndUnknownWritesNeverGoToJev() {
        var jev = mock(JevClient.class);
        for (String name : List.of("create-order", "unseen_write", "eval_publish_weibo")) {
            try (var h = new AgentHarness(jev)) {
                h.add(name, false); h.call(name, Map.of()); h.say("done");
                assertThat(h.start().getPendingConfirmations()).hasSize(1);
                assertThat(h.world.actions).isEmpty();
                verifyNoInteractions(jev);
            }
        }
    }

    @ParameterizedTest @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, 1.01, -0.01})
    void malformedJevProbabilitiesCannotAutoApprove(double probability) {
        var jev = mock(JevClient.class);
        when(jev.systemOne(any())).thenReturn(Mono.just(new SystemOneResult("mock",
                Map.of("consistent", new NoulAnswer(probability)), null)));
        try (var h = new AgentHarness(jev)) {
            h.add("auto-bind-coupons", false); h.call("auto-bind-coupons", Map.of()); h.say("done");
            assertThat(h.start().getPendingConfirmations()).hasSize(1);
            assertThat(h.world.actions).isEmpty();
        }
    }

    @Test void realRideAdaptersUseMockServiceAndRequireSeparateCreateConfirmation() throws Exception {
        try (var h = new AgentHarness()) {
            var service = mock(TencentRideService.class);
            when(service.execute(nullable(String.class), anyMap())).thenAnswer(inv -> {
                Map<String, Object> request = inv.getArgument(1);
                String command = (String) request.get("command");
                @SuppressWarnings("unchecked") Map<String,Object> args = new LinkedHashMap<>((Map<String,Object>) request.getOrDefault("parameters", Map.of()));
                if (request.containsKey("stateVersion")) args.put("stateVersion", request.get("stateVersion"));
                Object result = h.world.execute("create-order".equals(command) ? "ride-create" : command, args);
                return new ObjectMapper().writeValueAsString(result);
            });
            h.toolkit.registerAgentTool(new TencentRideTool(service));
            h.toolkit.registerAgentTool(new TencentRideCreateOrderTool(service));
            h.call("tencent_ride", Map.of("command", "estimate-price", "parameters", Map.of()));
            h.call("tencent_ride_create_order", Map.of("price_estimate_keys", List.of("SYNTHETIC-Q1"), "stateVersion", "SYNTHETIC-V1", "summary", "SYNTHETIC A → B，快车，18元"));
            h.say("done");
            var pending = h.start();
            assertThat(h.world.orders).isEmpty();
            assertThat(pending.getPendingConfirmations()).hasSize(1);
            h.decide(pending, true);
            assertThat(h.world.state).containsEntry("rideOrderId", "9007199254740995");
            assertThat(h.world.actions).containsExactly("estimate-price", "ride-create");
        }
    }

    @Test void rideCancellationAlwaysRequiresHumanEvenWithConfidentJev() throws Exception {
        var jev = mock(JevClient.class);
        when(jev.systemOne(any())).thenReturn(Mono.just(new SystemOneResult("mock", Map.of("consistent", new NoulAnswer(.99)), null)));
        try (var h = new AgentHarness(jev)) {
            h.addRide();
            h.call("tencent_ride", Map.of("command", "check-cancel-fee", "parameters", Map.of()));
            h.call("tencent_ride", Map.of("command", "cancel-order", "parameters", Map.of("orderId", "9007199254740995", "amountFen", 500), "summary", "取消订单，费用5元"));
            h.say("done");
            var pending = h.start();
            assertThat(pending.getPendingConfirmations()).hasSize(1);
            assertThat(h.world.state).doesNotContainKey("cancelled");
            verifyNoInteractions(jev);
            h.decide(pending, true);
            assertThat(h.world.state).containsEntry("cancelled", true);
        }
    }

    @Test void weiboReadDoesNotAuthorizePublication() {
        try (var h = new AgentHarness()) {
            h.add("eval_search_weibo", true); h.add("eval_publish_weibo", false);
            h.call("eval_search_weibo", Map.of("count", 10, "sort", "hot"));
            h.call("eval_publish_weibo", Map.of("text", "SYNTHETIC text")); h.say("done");
            var pending = h.start();
            assertThat(h.world.state).containsEntry("readCount", 1).doesNotContainKey("published");
            h.decide(pending, false);
            assertThat(h.world.state).doesNotContainKey("published");
        }
    }

    @Test void mockStateMachinesRejectInvalidAndUnresolvedTransitions() {
        var w = new ScenarioWorld();
        assertThatThrownBy(() -> w.execute("create-order", MEAL)).hasMessageContaining("price");
        w.storeAvailable = false;
        assertThatThrownBy(() -> w.execute("calculate-price", MEAL)).hasMessageContaining("unavailable");
        w.storeAvailable = true; w.stockAvailable = false;
        assertThatThrownBy(() -> w.execute("calculate-price", MEAL)).hasMessageContaining("unavailable");
        w.stockAvailable = true; w.couponValid = false;
        var coupon = new HashMap<>(MEAL); coupon.put("coupon", "EXPIRED");
        assertThatThrownBy(() -> w.execute("calculate-price", coupon)).hasMessageContaining("expired");
        assertThatThrownBy(() -> w.execute("calculate-price", Map.of("storeCode", "FAKE"))).hasMessageContaining("fabricated");
        w.execute("calculate-price", MEAL);
        var changed = new HashMap<>(MEAL); changed.put("quantity", 2);
        assertThatThrownBy(() -> w.execute("create-order", changed)).hasMessageContaining("current order");
        w.state.put("timeout", true); w.execute("create-order", MEAL);
        w.execute("query-order", Map.of());
        assertThatThrownBy(() -> w.execute("create-order", MEAL)).hasMessageContaining("unresolved");
        assertThat(w.orders).hasSize(1);
        assertThatThrownBy(() -> w.execute("ride-cancel", Map.of("amountFen", 0))).hasMessageContaining("fee");
        w.execute("check-cancel-fee", Map.of());
        w.execute("ride-cancel", Map.of("amountFen", 500));
        assertThat(w.state).containsEntry("cancelled", true);
        assertThatThrownBy(() -> w.execute("ride-create", Map.of("stateVersion", "OLD"))).hasMessageContaining("stale");
    }
}
