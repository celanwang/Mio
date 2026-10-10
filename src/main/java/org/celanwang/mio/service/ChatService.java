package org.celanwang.mio.service;

import org.celanwang.mio.config.ToolPolicies;
import org.celanwang.mio.memory.HabitDetector;
import org.celanwang.mio.memory.MioLongTermMemory;
import org.celanwang.mio.memory.TrustStore;
import org.celanwang.mio.model.dto.ChatRequest;
import org.celanwang.mio.model.vo.ChatResponse;
import org.celanwang.mio.model.vo.ToolConfirmation;
import org.celanwang.mio.trace.TraceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.extensions.judge.jev.Answer;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.NoulAnswer;
import io.agentscope.extensions.judge.jev.NoulQuestion;
import io.agentscope.extensions.judge.jev.SystemOneRequest;
import io.agentscope.extensions.judge.jev.example.JevModelRouterMiddleware;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    private final String modelName;
    private final String jevModel;
    private final TraceStore traceStore;
    private final TrustStore trustStore;
    private final MioLongTermMemory longTermMemory;
    private final HabitDetector habitDetector;
    private final ObjectMapper objectMapper;
    private final Set<String> runningSessions = ConcurrentHashMap.newKeySet();
    /** Jev 判定结果（toolCallId → 是否通过），供误拦信号比对；有界 LRU 防泄漏。 */
    private final Map<String, Boolean> jevVerdicts = boundedMap(500);
    /** 会话级自动放行 create-order 的时间，供误放行信号比对。 */
    private final Map<String, Long> autoApprovedOrderAt = boundedMap(500);

    private static <K, V> Map<K, V> boundedMap(int maxSize) {
        return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > maxSize;
            }
        });
    }

    public ChatService(ReActAgent agent,
                       ObjectProvider<JevClient> jevClient,
                       @Value("${app.jev.auto-approve-threshold:0.8}") double jevThreshold,
                       @Value("${app.dashscope.model-name:qwen3-max}") String modelName,
                       @Value("${app.jev.model:jev-latest}") String jevModel,
                       TraceStore traceStore,
                       TrustStore trustStore,
                       MioLongTermMemory longTermMemory,
                       HabitDetector habitDetector,
                       ObjectMapper objectMapper) {
        this.agent = agent;
        this.jevClient = jevClient.getIfAvailable();
        this.jevThreshold = jevThreshold;
        this.modelName = modelName;
        this.jevModel = jevModel;
        this.traceStore = traceStore;
        this.trustStore = trustStore;
        this.longTermMemory = longTermMemory;
        this.habitDetector = habitDetector;
        this.objectMapper = objectMapper;
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
                    traceStore.append(sessionId, "human", "ask", "等待人工确认",
                            "待确认操作：" + pending.stream().map(ToolUseBlock::getName).toList());
                    return Mono.just(response(sessionId, "请先选择是否允许下面的操作，再继续对话。", pending));
                }
                UserMessage message;
                if (hasDecisions) {
                    if (pending.isEmpty()) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "确认已失效，请开启新对话后重新提问。");
                    }
                    List<ConfirmResult> results = confirmResults(pending, decisions);
                    recordTrust(sessionId, pending, decisions);
                    traceStore.append(sessionId, "human", "confirm", "人工确认",
                            pending.stream()
                                    .map(call -> call.getName() + " → "
                                            + (decisions.stream()
                                                    .filter(d -> call.getId().equals(d.toolCallId()))
                                                    .findFirst().map(ChatRequest.Confirmation::approved)
                                                    .orElse(false) ? "允许" : "拒绝"))
                                    .toList().toString());
                    message = UserMessage.builder()
                            .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, results))
                            .build();
                } else {
                    if (!StringUtils.hasText(request.getMessage())) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入消息内容。");
                    }
                    traceStore.append(sessionId, "user", "user", "用户消息", request.getMessage());
                    message = new UserMessage(request.getMessage());
                }
                List<String> autoApproved = new ArrayList<>();
                return callWithJevApproval(message, context, autoApproved, sessionId)
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
                                          List<String> autoApproved, String sessionId) {
        int before = messageCount(context);
        return agent.call(List.of(message), context).flatMap(reply -> {
            String effectiveModel = traceRouting(sessionId, context);
            traceNewMessages(sessionId, context, before, effectiveModel);
            recordLongTermMemory(context);
            habitDetector.detectAsync();
            List<ToolUseBlock> pending = pendingCalls(context);
            if (pending.isEmpty() || jevClient == null
                    || pending.stream().anyMatch(call -> !ToolPolicies.GUARDED.contains(call.getName()))) {
                if (!pending.isEmpty()) {
                    traceStore.append(sessionId, "human", "ask", "等待人工确认",
                            "待确认操作：" + pending.stream().map(ToolUseBlock::getName).toList());
                }
                return Mono.just(reply);
            }
            return Flux.fromIterable(pending)
                    .flatMapSequential(call -> judgeWithJev(call, context, sessionId))
                    .collectList()
                    .flatMap(verdicts -> {
                        if (!verdicts.stream().allMatch(Boolean::booleanValue)) {
                            traceStore.append(sessionId, "human", "ask", "Jev 未通过，转人工确认",
                                    "待确认操作：" + pending.stream().map(ToolUseBlock::getName).toList());
                            return Mono.just(reply);
                        }
                        autoApproved.addAll(pending.stream().map(ToolUseBlock::getName).toList());
                        if (pending.stream().anyMatch(call -> "create-order".equals(call.getName()))) {
                            autoApprovedOrderAt.put(sessionId, System.currentTimeMillis());
                        }
                        List<ConfirmResult> results = pending.stream()
                                .map(call -> new ConfirmResult(true, call)).toList();
                        UserMessage resume = UserMessage.builder()
                                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, results))
                                .build();
                        return callWithJevApproval(resume, context, autoApproved, sessionId);
                    })
                    .onErrorResume(e -> {
                        log.warn("Jev 审核失败，转人工确认：{}", e.getMessage());
                        traceStore.append(sessionId, "human", "ask", "Jev 审核失败，转人工确认", e.getMessage());
                        return Mono.just(reply);
                    });
        });
    }

    /**
     * 手动回调长期记忆 record：AgentScope 2.0.4 的 STATIC_CONTROL record 回调绑定默认会话，
     * 本项目按请求自定义 sessionId，框架回调拿不到消息，故在此用当前会话上下文手动触发（异步、fail-open）。
     */
    private void recordLongTermMemory(RuntimeContext context) {
        AgentState state = agent.getAgentState(context);
        if (state != null) {
            longTermMemory.record(state.getContext()).subscribe();
        }
    }

    private Mono<Boolean> judgeWithJev(ToolUseBlock call, RuntimeContext context, String sessionId) {
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
            boolean pass = probability >= jevThreshold;
            jevVerdicts.put(call.getId(), pass);
            log.info("Jev 审核 {}：概率 {}，阈值 {}", call.getName(), probability, jevThreshold);
            traceStore.append(sessionId, "jev", "jev", "Jev 审核 " + call.getName() + "（" + jevModel + "）",
                    "概率 " + probability + " / 阈值 " + jevThreshold + (pass ? " → 自动执行" : " → 未通过")
                            + "，callId " + call.getId());
            return pass;
        });
    }

    private int messageCount(RuntimeContext context) {
        AgentState state = agent.getAgentState(context);
        return state == null ? 0 : state.getContext().size();
    }

    /** 读取 Jev 模型路由结果（若有），记录路由事件并返回本次实际生效的模型名。 */
    private String traceRouting(String sessionId, RuntimeContext context) {
        JevModelRouterMiddleware.RoutingDecision decision = JevModelRouterMiddleware.decision(context);
        if (decision == null || decision.probabilities() == null || decision.probabilities().isEmpty()) {
            return modelName;
        }
        String effectiveModel = decision.model() instanceof DashScopeChatModel dashScope
                ? dashScope.getModelName() : modelName;
        String chosen = decision.probabilities().entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse("?");
        traceStore.append(sessionId, "jev", "jev", "Jev 模型路由（" + jevModel + "）",
                "选择 " + chosen + " → " + effectiveModel
                        + "，置信度 " + decision.confidence()
                        + "，各选项概率 " + decision.probabilities());
        return effectiveModel;
    }

    /** 把本轮新增的助手文本、工具调用与工具结果写入执行链路。 */
    private void traceNewMessages(String sessionId, RuntimeContext context, int before,
                                  String effectiveModel) {
        AgentState state = agent.getAgentState(context);
        if (state == null) {
            return;
        }
        List<Msg> messages = state.getContext();
        for (int i = Math.max(before, 0); i < messages.size(); i++) {
            Msg message = messages.get(i);
            if (message.getRole() == MsgRole.ASSISTANT) {
                for (TextBlock block : message.getContentBlocks(TextBlock.class)) {
                    if (StringUtils.hasText(block.getText())) {
                        traceStore.append(sessionId, "qwen", "model",
                                "Qwen 输出（" + effectiveModel + "）", truncate(block.getText(), 4000));
                    }
                }
                for (ToolUseBlock call : message.getContentBlocks(ToolUseBlock.class)) {
                    traceStore.append(sessionId, "tool:" + call.getName(), "tool_call",
                            "Qwen 发起工具调用 " + call.getName() + "（" + effectiveModel + "）",
                            truncate(toJson(call.getInput()), 2000));
                    detectRegretSignal(sessionId, call.getName());
                }
            } else if (message.getRole() == MsgRole.TOOL) {
                for (ToolResultBlock result : message.getContentBlocks(ToolResultBlock.class)) {
                    traceStore.append(sessionId, "tool:" + result.getName(), "tool_result",
                            "工具返回 " + result.getName() + "（" + result.getState() + "）",
                            truncate(outputText(result), 2000));
                }
            }
        }
    }

    /**
     * 误放行信号（疑似）：Jev 自动放行 create-order 后 30 分钟内出现 cancel-order，
     * 说明自动执行的可能并非用户真实意图。只是统计信号（用户也可能正常改主意），供阈值调优参考。
     */
    private void detectRegretSignal(String sessionId, String toolName) {
        if (!"cancel-order".equals(toolName)) {
            return;
        }
        Long approvedAt = autoApprovedOrderAt.remove(sessionId);
        if (approvedAt != null && System.currentTimeMillis() - approvedAt < 30 * 60 * 1000) {
            traceStore.append(sessionId, "jev", "jev_mismatch", "Jev 误放行信号（疑似）",
                    "create-order 自动放行后 30 分钟内用户发起 cancel-order");
        }
    }

    private String outputText(ToolResultBlock result) {
        if (result.getOutput() == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (var block : result.getOutput()) {
            if (block instanceof TextBlock textBlock && StringUtils.hasText(textBlock.getText())) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(textBlock.getText());
            }
        }
        return text.toString();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "…";
    }

    /**
     * 组装 Jev 审核用的用户意图：最近 8 条用户消息 + 最近一条助手回复。
     * 助手回复常含下单摘要（技能红线要求 create-order 前展示），
     * 补入后「就要刚才那个」类指代表达才有可核对的依据。
     */
    private String userIntent(RuntimeContext context) {
        AgentState state = agent.getAgentState(context);
        if (state == null) {
            return "";
        }
        List<Msg> messages = state.getContext();
        StringBuilder userTexts = new StringBuilder();
        String lastAssistantText = null;
        int count = 0;
        for (int i = messages.size() - 1; i >= 0 && (count < 8 || lastAssistantText == null); i--) {
            Msg message = messages.get(i);
            if (!StringUtils.hasText(message.getTextContent())) {
                continue;
            }
            if (message.getRole() == MsgRole.USER && count < 8) {
                userTexts.insert(0, message.getTextContent() + "\n");
                count++;
            } else if (message.getRole() == MsgRole.ASSISTANT && lastAssistantText == null) {
                lastAssistantText = message.getTextContent();
            }
        }
        StringBuilder intent = new StringBuilder(userTexts);
        if (lastAssistantText != null) {
            intent.append("\n助手最近回复（供指代核对）：\n").append(truncate(lastAssistantText, 1000));
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

    /** 只有人工确认计入信任统计；Jev 自动放行不算用户授权，不计入。Jev 拦截后用户批准记为误拦信号。 */
    private void recordTrust(String sessionId, List<ToolUseBlock> pending,
                             List<ChatRequest.Confirmation> decisions) {
        for (ToolUseBlock call : pending) {
            boolean approved = decisions.stream()
                    .filter(d -> call.getId().equals(d.toolCallId()))
                    .findFirst().map(ChatRequest.Confirmation::approved)
                    .orElse(false);
            trustStore.record(call.getName(), call.getInput(), approved);
            Boolean jevPass = jevVerdicts.remove(call.getId());
            if (approved && Boolean.FALSE.equals(jevPass)) {
                traceStore.append(sessionId, "jev", "jev_mismatch", "Jev 误拦信号",
                        call.getName() + " 被 Jev 拦截，转人工后用户批准（callId " + call.getId() + "）");
            }
        }
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
