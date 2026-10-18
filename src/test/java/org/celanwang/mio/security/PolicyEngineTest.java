package org.celanwang.mio.security;

import org.celanwang.mio.config.ToolPolicies;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PolicyEngineTest {
    @ParameterizedTest
    @ValueSource(strings = {"shadow", "enforce"})
    void allKnownToolsKeepTheirPermissionBoundary(String mode) {
        var engine = new PolicyEngine(mode);
        ToolPolicies.READ_ONLY.forEach(tool ->
                assertThat(engine.evaluate(tool, false).effect()).as(tool).isEqualTo(PolicyEffect.ALLOW));
        ToolPolicies.HUMAN_ONLY.forEach(tool ->
                assertThat(engine.evaluate(tool, false).effect()).as(tool).isEqualTo(PolicyEffect.ASK));
        ToolPolicies.JEV_REVIEWABLE.forEach(tool ->
                assertThat(engine.evaluate(tool, false).effect()).as(tool).isEqualTo(PolicyEffect.ASK));
        assertThat(engine.evaluate("unseen_write", false).effect()).isEqualTo(PolicyEffect.ASK);
        assertThat(engine.evaluate("remote_read", true).effect()).isEqualTo(PolicyEffect.ALLOW);
    }

    @Test void killSwitchKeepsReadsAndAsksForEveryWrite() {
        var engine = new PolicyEngine("shadow");
        engine.setCircuitOpen(true);
        ToolPolicies.READ_ONLY.forEach(tool ->
                assertThat(engine.evaluate(tool, false).effect()).isEqualTo(PolicyEffect.ALLOW));
        var writes = new java.util.HashSet<>(ToolPolicies.HUMAN_ONLY);
        writes.addAll(ToolPolicies.JEV_REVIEWABLE);
        writes.add("unseen_write");
        writes.forEach(tool -> {
            assertThat(engine.evaluate(tool, false).effect()).isEqualTo(PolicyEffect.ASK);
            assertThat(engine.evaluate(tool, false).source()).isEqualTo("kill-switch");
        });
        engine.setCircuitOpen(false);
        assertThat(engine.evaluate("auto-bind-coupons", false).source()).isEqualTo("default-ask");
    }

    @Test void permissionTracksNeverOverlap() {
        assertThat(ToolPolicies.READ_ONLY).doesNotContainAnyElementsOf(ToolPolicies.HUMAN_ONLY)
                .doesNotContainAnyElementsOf(ToolPolicies.JEV_REVIEWABLE);
        assertThat(ToolPolicies.HUMAN_ONLY).doesNotContainAnyElementsOf(ToolPolicies.JEV_REVIEWABLE);
        assertThat(new PolicyEngine("shadow").isEnforce()).isFalse();
        assertThat(new PolicyEngine(" ENFORCE ").isEnforce()).isTrue();
    }
}
