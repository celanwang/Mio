package org.celanwang.mio.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 长期记忆挂点：retrieve 注入用户画像，record 落盘会话消息并触发画像提炼。
 * 失败方向 fail-open：任何异常只记日志，绝不阻断对话。
 */
@Component
public class MioLongTermMemory implements LongTermMemory {

    private static final Logger log = LoggerFactory.getLogger(MioLongTermMemory.class);
    private static final int MAX_RECENT_HASHES = 2000;

    private final ProfileStore profileStore;
    private final ProfileDistiller distiller;
    private final AutomationStore automationStore;
    private final Path episodesFile;
    private final ObjectMapper objectMapper;
    /** 已写入 episodes 的消息指纹（record 每次收到全量历史，靠它去重）。 */
    private final Set<String> recentHashes = new LinkedHashSet<>();

    public MioLongTermMemory(@Value("${app.memory.dir:./.mio}") String dir,
                             ProfileStore profileStore,
                             ProfileDistiller distiller,
                             AutomationStore automationStore,
                             ObjectMapper objectMapper) {
        this.episodesFile = JsonFileSupport.expandHome(dir).resolve("memory/episodes.jsonl");
        this.profileStore = profileStore;
        this.distiller = distiller;
        this.automationStore = automationStore;
        this.objectMapper = objectMapper;
    }

    /**
     * 返回注入文本（画像 + 自动化提议 + 已授权例行事项），由框架（STATIC_CONTROL 模式）
     * 注入到本次调用的上下文中；无任何内容时返回空串。全部标注为参考信息而非指令。
     */
    @Override
    public Mono<String> retrieve(Msg msg) {
        return Mono.fromCallable(this::renderInjection)
                .onErrorResume(e -> {
                    log.warn("记忆注入读取失败（fail-open，按空内容注入）：{}", e.getMessage());
                    return Mono.just("");
                });
    }

    private String renderInjection() {
        StringBuilder text = new StringBuilder(profileStore.render());
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
        return text.toString();
    }

    /**
     * 框架在 Agent 调用后回调（STATIC_CONTROL + asyncRecord）。
     * 注意：AgentScope 2.0.4 的框架回调绑定默认会话，自定义 sessionId 下不会触发，
     * 实际由 ChatService 在每轮调用后用当前会话上下文手动调用本方法；recentHashes 去重保证不重复写入。
     */
    @Override
    public Mono<Void> record(List<Msg> msgs) {
        return Mono.fromRunnable(() -> {
                    String lastUserText = recordEpisodes(msgs);
                    distiller.distill(lastUserText);
                })
                .doOnError(e -> log.warn("长期记忆写入失败（fail-open）：{}", e.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    /** 把未记录过的 user/assistant 文本消息追加到 episodes.jsonl，返回最近一条用户消息文本。 */
    private String recordEpisodes(List<Msg> msgs) {
        String lastUserText = null;
        for (Msg msg : msgs) {
            if (msg.getRole() != MsgRole.USER && msg.getRole() != MsgRole.ASSISTANT) {
                continue;
            }
            String text = msg.getTextContent();
            if (!StringUtils.hasText(text) || !markSeen(msg.getRole() + "\n" + text)) {
                continue;
            }
            if (msg.getRole() == MsgRole.USER) {
                lastUserText = text;
            }
            appendEpisode(msg.getRole(), text);
        }
        return lastUserText;
    }

    private void appendEpisode(MsgRole role, String text) {
        try {
            Files.createDirectories(episodesFile.getParent());
            String line = objectMapper.writeValueAsString(new Episode(
                    System.currentTimeMillis(),
                    role == MsgRole.USER ? "user" : "assistant", text));
            Files.writeString(episodesFile, line + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.warn("会话消息落盘失败（不影响主流程）：{}", e.getMessage());
        }
    }

    private synchronized boolean markSeen(String hash) {
        if (recentHashes.size() >= MAX_RECENT_HASHES) {
            recentHashes.remove(recentHashes.iterator().next());
        }
        return recentHashes.add(hash);
    }

    /** episodes.jsonl 的单行结构。 */
    public record Episode(long timestamp, String role, String text) {
    }
}
