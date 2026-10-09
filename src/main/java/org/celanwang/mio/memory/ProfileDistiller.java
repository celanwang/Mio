package org.celanwang.mio.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 画像提炼器：用轻量模型从最近的用户消息中提取显式表达的用户偏好写入画像。
 * 只提取显式偏好，敏感主题不提取；任何解析/模型异常 fail-open 忽略，只记日志。
 */
@Component
public class ProfileDistiller {

    private static final Logger log = LoggerFactory.getLogger(ProfileDistiller.class);

    private static final String PROMPT = """
            从下面用户最近说的话中提取「显式表达的用户偏好」，只输出严格的 JSON 数组，不要输出任何其他内容。
            规则：
            - 只提取用户明确说出的偏好（口味/忌口、常用地址、预算、回复风格等），拿不准的不要提取；
            - 健康、政治、宗教等敏感主题一律不提取；
            - 没有可提取内容时输出 []；
            - 每条格式：{"key":"偏好名","value":"偏好内容","scope":"global","confidence":0.9}
            用户的话：
            """;

    private final Model fastModel;
    private final ProfileStore profileStore;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "profile-distiller");
        thread.setDaemon(true);
        return thread;
    });

    public ProfileDistiller(@Qualifier("dashScopeFastModel") Model fastModel,
                            ProfileStore profileStore,
                            ObjectMapper objectMapper,
                            @Value("${app.memory.distiller.enabled:true}") boolean enabled) {
        this.fastModel = fastModel;
        this.profileStore = profileStore;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    /** 异步提炼一条用户消息；开关关闭或文本为空时直接跳过。 */
    public void distill(String userText) {
        if (!enabled || !StringUtils.hasText(userText)) {
            return;
        }
        executor.execute(() -> {
            try {
                doDistill(userText);
            } catch (Exception e) {
                log.warn("画像提炼失败（fail-open，忽略）：{}", e.getMessage());
            }
        });
    }

    private void doDistill(String userText) throws Exception {
        var responses = fastModel.stream(List.of(new UserMessage(PROMPT + userText)), null,
                        GenerateOptions.builder().build())
                .collectList().block();
        if (responses == null || responses.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (var block : responses.get(responses.size() - 1).getContent()) {
            if (block instanceof TextBlock textBlock) {
                text.append(textBlock.getText());
            }
        }
        String json = extractJsonArray(text.toString());
        if (json == null) {
            log.warn("画像提炼输出不是 JSON 数组，忽略：{}", text);
            return;
        }
        List<Extracted> extracted = objectMapper.readValue(json,
                objectMapper.getTypeFactory().constructCollectionType(List.class, Extracted.class));
        for (Extracted item : extracted) {
            if (item == null || !StringUtils.hasText(item.key()) || !StringUtils.hasText(item.value())) {
                continue;
            }
            String scope = StringUtils.hasText(item.scope()) ? item.scope().trim() : ProfileStore.SCOPE_GLOBAL;
            double confidence = item.confidence() == null ? 0.8
                    : Math.max(0.0, Math.min(1.0, item.confidence()));
            profileStore.upsert(new ProfileEntry(item.key().trim(), item.value().trim(), scope,
                    ProfileStore.SOURCE_INFERRED, confidence, System.currentTimeMillis(), null));
            log.info("画像提炼新增/更新：{} = {}（{}）", item.key(), item.value(), scope);
        }
    }

    /** 容错截取模型输出中的 JSON 数组部分。 */
    private String extractJsonArray(String text) {
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        return start >= 0 && end > start ? text.substring(start, end + 1) : null;
    }

    /** 提炼输出的单条结构（宽松解析，字段可缺省）。 */
    public record Extracted(String key, String value, String scope, Double confidence) {
    }
}
