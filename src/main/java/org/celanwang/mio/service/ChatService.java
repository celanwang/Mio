package org.celanwang.mio.service;

import org.celanwang.mio.config.ToolPolicies;
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
import io.agentscope.extensions.judge.jev.Answer;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.NoulAnswer;
import io.agentscope.extensions.judge.jev.NoulQuestion;
import io.agentscope.extensions.judge.jev.SystemOneRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ReActAgent agent;
    private final JevClient jevClient;
    private final double jevThreshold;
    private final Set<String> runningSessions = ConcurrentHashMap.newKeySet();

    public ChatService(ReActAgent agent,
                       ObjectProvider<JevClient> jevClient,
                       @Value("${app.jev.auto-approve-threshold:0.8}") double jevThreshold) {
        this.agent = agent;
        this.jevClient = jevClient.getIfAvailable();
        this.jevThreshold = jevThreshold;
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
                List<String> autoApproved = new ArrayList<>();
                return callWithJevApproval(message, context, autoApproved)
                        .map(reply -> {
                            String text = reply.getTextContent();
                            if (!autoApproved.isEmpty()) {
                                String note = "（Jev 已审核通过并自动执行：" + String.join("、", autoApproved) + "）";
                                text = StringUtils.hasText(text) ? note + "\n\n" + text : note;
                            }
                            return response(sessionId, text, pendingCalls(context));
                        });
            }).doFinally(signal -> runningSessions.remove(sessionId));
        });
    }

    /**
     * 执行 Agent 调用；遇到待审核的写操作时先由 Jev 判断与用户意图的一致性，
     * 全部通过则自动放行并继续执行，任一不通过或 Jev 不可用则转人工确认。
     */
    private Mono<Msg> callWithJevApproval(UserMessage message, RuntimeContext context,
                                          List<String> autoApproved) {
        return agent.call(List.of(message), context).flatMap(reply -> {
            List<ToolUseBlock> pending = pendingCalls(context);
            if (pending.isEmpty() || jevClient == null
                    || pending.stream().anyMatch(call -> !ToolPolicies.GUARDED.contains(call.getName()))) {
                return Mono.just(reply);
            }
            return Flux.fromIterable(pending)
                    .flatMapSequential(call -> judgeWithJev(call, context))
                    .collectList()
                    .flatMap(verdicts -> {
                        if (!verdicts.stream().allMatch(Boolean::booleanValue)) {
                            return Mono.just(reply);
                        }
                        autoApproved.addAll(pending.stream().map(ToolUseBlock::getName).toList());
                        List<ConfirmResult> results = pending.stream()
                                .map(call -> new ConfirmResult(true, call)).toList();
                        UserMessage resume = UserMessage.builder()
                                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, results))
                                .build();
                        return callWithJevApproval(resume, context, autoApproved);
                    })
                    .onErrorResume(e -> {
                        log.warn("Jev 审核失败，转人工确认：{}", e.getMessage());
                        return Mono.just(reply);
                    });
        });
    }

    private Mono<Boolean> judgeWithJev(ToolUseBlock call, RuntimeContext context) {
        Map<String, Object> state = Map.of(
                "用户需求", userIntent(context),
                "待执行操作", call.getName(),
                "操作参数", call.getInput() == null ? Map.of() : call.getInput());
        SystemOneRequest request = SystemOneRequest.builder()
                .state(state)
                .question("consistent", new NoulQuestion(
                        "判断「待执行操作」及其参数是否严格符合「用户需求」中用户明确表达的要求"
                                + "（商品、数量、预算、地址、门店、时间等），未超出用户授权范围，"
                                + "且参数无明显异常或编造。完全符合则为真，否则为假。", null))
                .build();
        return jevClient.systemOne(request).map(result -> {
            Answer answer = result.answers().get("consistent");
            double probability = answer instanceof NoulAnswer noul && noul.noul() != null
                    ? noul.noul() : 0.0;
            log.info("Jev 审核 {}：概率 {}，阈值 {}", call.getName(), probability, jevThreshold);
            return probability >= jevThreshold;
        });
    }

    private String userIntent(RuntimeContext context) {
        AgentState state = agent.getAgentState(context);
        if (state == null) {
            return "";
        }
        List<Msg> messages = state.getContext();
        StringBuilder intent = new StringBuilder();
        int count = 0;
        for (int i = messages.size() - 1; i >= 0 && count < 8; i--) {
            Msg message = messages.get(i);
            if (message.getRole() == MsgRole.USER && StringUtils.hasText(message.getTextContent())) {
                intent.insert(0, message.getTextContent() + "\n");
                count++;
            }
        }
        return intent.toString().trim();
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
