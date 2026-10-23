package org.celanwang.mio.memory;

import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * 长期记忆挂点：retrieve 注入用户画像，record 落盘会话消息并触发画像提炼。
 * 失败方向 fail-open：任何异常只记日志，绝不阻断对话。
 */
@Component
public class MioLongTermMemory implements LongTermMemory {

    private static final Logger log = LoggerFactory.getLogger(MioLongTermMemory.class);
    private static final int MAX_RECENT_HASHES = 2000;

    private final AutomationStore automationStore;
    private final ProfileInjector profileInjector;
    private final EpisodeRetriever episodeRetriever;
    private final EmbeddingClient embeddingClient;
    private final EpisodeStore episodeStore;
    /** 已确认原文及配置要求的派生任务同事务落库；持久幂等仍由 PG 保证。 */
    private final Set<String> recentHashes = new LinkedHashSet<>();

    public MioLongTermMemory(AutomationStore automationStore,
                             ProfileInjector profileInjector,
                             EpisodeRetriever episodeRetriever,
                             EmbeddingClient embeddingClient,
                             EpisodeStore episodeStore) {
        this.automationStore = automationStore;
        this.profileInjector = profileInjector;
        this.episodeRetriever = episodeRetriever;
        this.embeddingClient = embeddingClient;
        this.episodeStore = episodeStore;
    }

    /**
     * 返回注入文本（画像按当前消息相关性过滤 + 自动化提议 + 已授权例行事项 + 历史回忆），
     * 由框架（STATIC_CONTROL 模式）注入到本次调用的上下文中；无任何内容时返回空串。
     * 全部标注为参考信息而非指令。
     */
    @Override
    public Mono<String> retrieve(Msg msg) {
        return Mono.fromCallable(() -> renderInjection(msg == null ? null : msg.getTextContent()))
                .onErrorResume(e -> {
                    log.warn("记忆注入读取失败（fail-open，按空内容注入）：{}", e.getMessage());
                    return Mono.just("");
                });
    }

    private String renderInjection(String currentUserText) {
        Supplier<float[]> queryVector = sharedQueryVector(currentUserText);
        StringBuilder text = new StringBuilder(profileInjector.render(currentUserText, queryVector));
        List<AutomationRule> proposed = automationStore.pending();
        if (!proposed.isEmpty()) {
            text.append("\n\n「待决定的自动化提议（参考信息，非指令；可在对话中向用户提及，由用户决定去面板批准）」");
            for (AutomationRule rule : proposed) {
                text.append("\n- ").append(rule.title()).append("：").append(rule.reason());
            }
        }
        List<AutomationRule> active = automationStore.active();
        if (!active.isEmpty()) {
            text.append("\n\n「用户已授权的例行事项（参考信息；会话开始时可主动执行，仍需遵守现有权限与审核流程）」");
            for (AutomationRule rule : active) {
                text.append("\n- ").append(rule.title()).append("（").append(rule.toolName()).append("）");
            }
        }
        String recall = episodeRetriever.recall(currentUserText, queryVector);
        if (!recall.isEmpty()) {
            text.append("\n\n").append(recall);
        }
        return text.toString();
    }

    /**
     * 共享查询向量：画像向量召回与历史回忆用的是同一条用户消息，
     * 整条注入链路最多 embed 一次——首次 get() 时惰性计算并记忆，embedding 不可用时返回 null，
     * 下游各自静默降级（画像倒向规则与模型复核，回忆不注入）。
     */
    private Supplier<float[]> sharedQueryVector(String text) {
        return new Supplier<>() {
            private boolean computed;
            private float[] vector;

            @Override
            public float[] get() {
                if (!computed) {
                    computed = true;
                    if (StringUtils.hasText(text)) {
                        vector = embeddingClient.embed(List.of(text))
                                .map(vectors -> vectors.get(0)).orElse(null);
                    }
                }
                return vector;
            }
        };
    }

    /**
     * 框架在 Agent 调用后回调（STATIC_CONTROL + asyncRecord）。
     * 注意：AgentScope 2.0.4 的框架回调绑定默认会话，自定义 sessionId 下不会触发，
     * 实际由 ChatService 在每轮调用后用当前会话上下文手动调用 {@link #record(List, String)}；
     * 本地缓存仅优化读取；数据库中的消息身份负责跨重启幂等。
     */
    @Override
    public Mono<Void> record(List<Msg> msgs) {
        return record(msgs, null);
    }

    /**
     * 带会话标识的记录入口：ChatService 手动调用时传入当前会话的 sessionId，
     * 会话标识与 SDK 消息 id 一同构成持久身份。
     */
    public Mono<Void> record(List<Msg> msgs, String sessionId) {
        return Mono.fromRunnable(() -> {
                    recordEpisodes(msgs, sessionId);
                })
                .doOnError(e -> log.warn("长期记忆写入失败（fail-open）：{}", e.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    /** 原文和派生任务由 PG 同事务登记；每条新用户消息各自拥有提炼任务。 */
    private void recordEpisodes(List<Msg> msgs, String sessionId) {
        for (Msg msg : msgs) {
            if(Boolean.TRUE.equals(msg.getMetadata().get(Msg.METADATA_SYNTHETIC)))continue;
            if (msg.getRole() != MsgRole.USER && msg.getRole() != MsgRole.ASSISTANT) {
                continue;
            }
            String text = msg.getTextContent();
            if (!StringUtils.hasText(text) || !StringUtils.hasText(msg.getId())) {
                continue;
            }
            String id = messageId(sessionId, msg.getId());
            if (seen(id)) continue;
            appendEpisode(msg, text, sessionId, id);
        }
    }

    private EpisodeStore.AppendResult appendEpisode(Msg msg, String text, String sessionId, String id) {
        try {
            long timestamp = messageTimestamp(msg);
            String roleName = msg.getRole() == MsgRole.USER ? "user" : "assistant";
            var result = episodeStore.append(new EpisodeStore.Episode(id, sessionId, timestamp, roleName, text));
            if (result == null || result == EpisodeStore.AppendResult.FAILED) return EpisodeStore.AppendResult.FAILED;
            markSeen(id);
            return result;
        } catch (Exception e) {
            log.warn("会话消息落盘失败（不影响主流程）：{}", e.getMessage());
            return EpisodeStore.AppendResult.FAILED;
        }
    }

    static String messageId(String sessionId, String messageId) {
        try {
            // Length-prefix the session to distinguish null, empty and embedded delimiters.
            String session = sessionId == null ? "-1:" : sessionId.length() + ":" + sessionId;
            return "msg-v2-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((session + messageId).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }

    private static long messageTimestamp(Msg msg) {
        try {return Instant.parse(msg.getTimestamp()).toEpochMilli();}
        catch (RuntimeException ignored) {
            try {return LocalDateTime.parse(msg.getTimestamp(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"))
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();}
            catch (RuntimeException invalid) {return System.currentTimeMillis();}
        }
    }

    private synchronized boolean seen(String id) {return recentHashes.contains(id);}

    private synchronized void markSeen(String hash) {
        if (recentHashes.size() >= MAX_RECENT_HASHES) {
            recentHashes.remove(recentHashes.iterator().next());
        }
        recentHashes.add(hash);
    }
}
