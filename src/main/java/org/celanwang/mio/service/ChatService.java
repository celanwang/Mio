package org.celanwang.mio.service;

import org.celanwang.mio.model.dto.ChatRequest;
import org.celanwang.mio.model.vo.ChatResponse;
import org.celanwang.mio.model.vo.ToolConfirmation;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ChatService {
    private final ReActAgent agent;
    private final Set<String> runningSessions = ConcurrentHashMap.newKeySet();

    public ChatService(ReActAgent agent) {
        this.agent = agent;
    }

    public Mono<ChatResponse> chat(ChatRequest request) {
        String sessionId = StringUtils.hasText(request.getSessionId())
                ? request.getSessionId() : UUID.randomUUID().toString();
        RuntimeContext context = RuntimeContext.builder().sessionId(sessionId).build();
        return Mono.defer(() -> {
            if (!runningSessions.add(sessionId)) {
                return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "当前对话正在处理，请稍候。"));
            }
            return Mono.defer(() -> {
                List<ToolUseBlock> pending = pendingCalls(context);
                List<ChatRequest.Confirmation> decisions = request.getConfirmations();
                boolean hasDecisions = decisions != null && !decisions.isEmpty();
                if (!pending.isEmpty() && !hasDecisions) {
                    // 已暂停时直接重新展示确认卡片，避免把普通提问交给暂停中的 Agent。
                    return Mono.just(response(sessionId, "请先选择是否允许下面的操作，再继续对话。", pending));
                }
                UserMessage message;
                if (hasDecisions) {
                    if (pending.isEmpty()) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "确认已失效，请开启新对话后重新提问。");
                    }
                    List<ConfirmResult> results = confirmResults(pending, decisions);
                    message = UserMessage.builder()
                            .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, results))
                            .build();
                } else {
                    if (!StringUtils.hasText(request.getMessage())) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入消息内容。");
                    }
                    message = new UserMessage(request.getMessage());
                }
                return agent.call(List.of(message), context)
                        .map(reply -> response(sessionId, reply.getTextContent(), pendingCalls(context)));
            }).doFinally(signal -> runningSessions.remove(sessionId));
        });
    }

    private List<ToolUseBlock> pendingCalls(RuntimeContext context) {
        AgentState state = agent.getAgentState(context);
        if (state == null) return List.of();
        List<Msg> messages = state.getContext();
        // 与 AgentScope 一致，只查看最后一条助手消息中的 ASKING 状态。
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg message = messages.get(i);
            if (message.getRole() == MsgRole.ASSISTANT) {
                return message.getContentBlocks(ToolUseBlock.class).stream()
                        .filter(call -> call.getState() == ToolCallState.ASKING).toList();
            }
        }
        return List.of();
    }

    private List<ConfirmResult> confirmResults(List<ToolUseBlock> pending,
                                              List<ChatRequest.Confirmation> decisions) {
        Map<String, Boolean> choices = new HashMap<>();
        for (ChatRequest.Confirmation decision : decisions) {
            if (decision == null || decision.approved() == null
                    || choices.putIfAbsent(decision.toolCallId(), decision.approved()) != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "确认结果缺失或重复，请刷新页面重试。");
            }
        }
        if (choices.size() != pending.size()
                || pending.stream().anyMatch(call -> !choices.containsKey(call.getId()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "确认结果与当前待执行操作不匹配。");
        }
        // 只批准当前这一组调用，不添加永久放行规则。
        return pending.stream().map(call -> new ConfirmResult(choices.get(call.getId()), call)).toList();
    }

    private ChatResponse response(String sessionId, String text, List<ToolUseBlock> pending) {
        List<ToolConfirmation> confirmations = pending.stream().map(call -> {
            var tool = agent.getToolkit().getTool(call.getName());
            String description = tool == null ? call.getName() : tool.getDescription();
            return new ToolConfirmation(call.getId(), call.getName(), description, call.getInput());
        }).toList();
        String reply = StringUtils.hasText(text) ? text
                : pending.isEmpty() ? "本次处理已结束。" : "需要你确认以下操作后，助手才能继续。";
        return new ChatResponse(sessionId, reply, confirmations);
    }
}
