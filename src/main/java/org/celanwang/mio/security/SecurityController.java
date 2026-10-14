package org.celanwang.mio.security;

import org.celanwang.mio.memory.AutomationStore;
import org.celanwang.mio.trace.TraceStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 急停开关：开启后非只读工具一律转人工（自动审核轨停用），并吊销全部 ACTIVE 自动化授权。
 * 接口本身由本机回环拦截器保护。
 */
@RestController
@RequestMapping("/api/security")
public class SecurityController {

    private final PolicyEngine policyEngine;
    private final AutomationStore automationStore;
    private final TraceStore traceStore;

    public SecurityController(PolicyEngine policyEngine, AutomationStore automationStore, TraceStore traceStore) {
        this.policyEngine = policyEngine;
        this.automationStore = automationStore;
        this.traceStore = traceStore;
    }

    @GetMapping("/kill-switch")
    public Map<String, Object> killSwitch() {
        return Map.of("enabled", policyEngine.isCircuitOpen());
    }

    @PostMapping("/kill-switch")
    public Map<String, Object> killSwitch(@RequestBody Map<String, Boolean> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        policyEngine.setCircuitOpen(enabled);
        int revoked = enabled ? automationStore.revokeAllActive() : 0;
        traceStore.append("system", "policy", "policy_decision",
                enabled ? "急停开启" : "急停关闭",
                enabled ? "非只读工具一律转人工，吊销 ACTIVE 自动化 " + revoked + " 条" : "恢复正常裁决");
        return Map.of("enabled", enabled, "revokedAutomations", revoked);
    }
}
