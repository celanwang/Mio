package org.celanwang.mio.tool;

import org.celanwang.mio.service.WeiboMcpService;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/** 本机准备步骤不执行业务查询、点赞或发布，也不代替用户完成 OAuth。 */
public class WeiboConnectionTool extends ToolBase {
    public static final String NAME = "prepare_weibo";
    private final WeiboMcpService weibo;

    public WeiboConnectionTool(WeiboMcpService weibo) {
        super(ToolBase.builder().name(NAME)
                .description("仅在用户要求微博查询或互动时调用：检查已有授权并按需连接微博。返回 ready 才可继续微博业务；authorization_required 时暂停并等待对话中的账号授权，retry_required 时说明失败并等待用户重试。普通聊天和功能说明不要调用。")
                .inputSchema(Map.of("type", "object", "properties", Map.of(), "additionalProperties", false))
                .readOnly(true).concurrencySafe(true));
        this.weibo = weibo;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> input, PermissionContextState context) {
        return Mono.just(PermissionDecision.allow("只检查本机授权和建立微博连接，不执行业务操作。"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam call) {
        return Mono.fromCallable(() -> {
            String sessionId = call.getRuntimeContext() == null ? null : call.getRuntimeContext().getSessionId();
            if (sessionId == null || sessionId.isBlank()) return ToolResultBlock.error("缺少对话标识，请开启新对话。");
            return ToolResultBlock.text(weibo.prepareForConversation(sessionId));
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
