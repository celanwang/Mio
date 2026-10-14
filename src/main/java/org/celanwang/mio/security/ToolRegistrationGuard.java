package org.celanwang.mio.security;

import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.Toolkit;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * 工具注册守卫：防多来源工具同名遮蔽（Toolkit 重名静默覆盖），
 * 并断言被注册工具是 ToolBase 实例（非 ToolBase 工具会绕过权限引擎直接放行）。
 * 冲突即失败，fail-closed，与技能缺失即启动失败的纪律一致。
 */
@Component
public class ToolRegistrationGuard {

    /** 本地工具注册前检查：必须是 ToolBase 且名称未被占用。 */
    public void checkLocalTool(Toolkit toolkit, ToolBase tool, String source) {
        if (snapshot(toolkit).contains(tool.getName())) {
            throw new IllegalStateException("来源 " + source + " 的工具 " + tool.getName()
                    + " 与已注册工具重名，拒绝注册（防工具遮蔽）。");
        }
    }

    public Set<String> snapshot(Toolkit toolkit) {
        return new HashSet<>(toolkit.getToolNames());
    }

    /**
     * MCP 注册后校验：新增工具数必须等于远端声明数，且新工具均为 ToolBase。
     * 数量不符说明发生了同名静默覆盖（无法逐个对应注册名，按集合差校验）。
     */
    public void verifyMcpRegistration(Toolkit toolkit, Set<String> before, int declaredCount, String source) {
        Set<String> after = snapshot(toolkit);
        if (!after.containsAll(before) || after.size() - before.size() != declaredCount) {
            throw new IllegalStateException("来源 " + source + " 的工具注册发生同名冲突：声明 "
                    + declaredCount + " 个，实际新增 " + (after.size() - before.size()) + " 个（防工具遮蔽）。");
        }
        for (String name : after) {
            if (!before.contains(name) && !(toolkit.getTool(name) instanceof ToolBase)) {
                throw new IllegalStateException("来源 " + source + " 的工具 " + name
                        + " 不是 ToolBase 实例，会绕过权限引擎，拒绝注册。");
            }
        }
    }
}
