package org.celanwang.mio.security;

import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import org.celanwang.mio.config.ToolPolicies;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 唯一裁决点：所有工具调用的放行/询问/拒绝由此给出，规则只按工具名匹配（不做参数级规则）。
 * 求值优先级 deny > ask > allow；兜底默认 ASK（框架 PermissionMode.DEFAULT 语义即转人工）。
 * shadow 模式下 permissionContext() 与历史行为完全一致，裁决仅用于审计留痕；
 * enforce 模式下硬编码 DENY 规则安装进权限上下文，真正生效。
 */
@Component
public class PolicyEngine {

    /** 硬编码拒绝：不可被任何 allow 规则覆盖。初版为空，预留接缝。 */
    private static final Set<String> DENY = Set.of();

    private final boolean enforce;
    /** 急停状态：开启时非只读工具一律转人工，自动审核轨停用（见 ChatService）。 */
    private volatile boolean circuitOpen;

    public PolicyEngine(@Value("${app.security.policy-mode:shadow}") String policyMode) {
        this.enforce = "enforce".equalsIgnoreCase(policyMode.trim());
    }

    /** 裁决一个工具调用。readOnlyTool 为工具自声明的只读注解（MCP readOnlyHint）。 */
    public PolicyDecision evaluate(String toolName, boolean readOnlyTool) {
        if (DENY.contains(toolName)) {
            return new PolicyDecision(PolicyEffect.DENY, "hardcoded-deny", "命中硬编码拒绝规则");
        }
        boolean readOnly = ToolPolicies.READ_ONLY.contains(toolName) || readOnlyTool;
        if (circuitOpen && !readOnly) {
            return new PolicyDecision(PolicyEffect.ASK, "kill-switch", "急停开启，非只读操作一律转人工");
        }
        if (readOnly) {
            return new PolicyDecision(PolicyEffect.ALLOW, "static-allow", "只读工具自动放行");
        }
        return new PolicyDecision(PolicyEffect.ASK, "default-ask", "无规则覆盖，默认转人工确认");
    }

    /**
     * 构建框架权限上下文：只读工具注册 allow 规则，其余回落默认 ASK。
     * enforce 模式下追加硬编码 DENY 规则（deny 优先于一切，框架保证）。
     */
    public PermissionContextState permissionContext() {
        var builder = PermissionContextState.builder().mode(PermissionMode.DEFAULT);
        for (String tool : ToolPolicies.READ_ONLY) {
            builder.addAllowRule(tool, new PermissionRule(tool, "", PermissionBehavior.ALLOW, "mio"));
        }
        if (enforce) {
            for (String tool : DENY) {
                builder.addDenyRule(tool, new PermissionRule(tool, "", PermissionBehavior.DENY, "mio"));
            }
        }
        return builder.build();
    }

    public boolean isCircuitOpen() {
        return circuitOpen;
    }

    public void setCircuitOpen(boolean circuitOpen) {
        this.circuitOpen = circuitOpen;
    }

    public boolean isEnforce() {
        return enforce;
    }
}
