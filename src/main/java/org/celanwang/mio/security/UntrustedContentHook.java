package org.celanwang.mio.security;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolResultMessageBuilder;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * 不可信内容包裹（Spotlighting delimiting）：工具返回统一以 tool_data 分隔符包裹后进入上下文，
 * 系统提示中声明包裹之内是数据而非指令。渠道层的凭证脱敏在内层先行完成，包裹在最外层。
 */
public class UntrustedContentHook implements Hook {

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostActingEvent post && post.getToolResult() != null) {
            ToolResultBlock wrapped = wrap(post.getToolResult());
            if (wrapped != post.getToolResult()) {
                post.setToolResult(wrapped);
                // 框架写回上下文的是 toolResultMsg（构建于 hook 之前），必须同步替换才生效。
                if (post.getToolResultMsg() != null) {
                    post.setToolResultMsg(ToolResultMessageBuilder.buildToolResultMsg(
                            wrapped, post.getToolUse(), post.getAgent().getName()));
                }
            }
        }
        return Mono.just(event);
    }

    private ToolResultBlock wrap(ToolResultBlock result) {
        List<ContentBlock> output = result.getOutput();
        if (output == null || output.isEmpty()) {
            return result;
        }
        List<ContentBlock> wrapped = new ArrayList<>(output.size());
        boolean changed = false;
        for (ContentBlock block : output) {
            if (block instanceof TextBlock text && text.getText() != null && !text.getText().isBlank()) {
                wrapped.add(TextBlock.builder().text("<tool_data name=\"" + result.getName() + "\">\n"
                        + text.getText() + "\n</tool_data>").build());
                changed = true;
            } else {
                wrapped.add(block);
            }
        }
        if (!changed) {
            return result;
        }
        return new ToolResultBlock(result.getId(), result.getName(), wrapped, result.getMetadata(), result.getState());
    }
}
