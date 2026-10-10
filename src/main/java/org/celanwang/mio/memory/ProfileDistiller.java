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

/**
 * 画像提炼器：用轻量模型把用户显式表达的偏好维护进画像 Wiki（整页改写），
 * 并顺手产出实体关系三元组写入实体簿。
 * 只提取显式偏好，敏感主题不提取；任何解析/模型异常 fail-open 忽略，只记日志。
 */
@Component
public class ProfileDistiller {

    private static final Logger log = LoggerFactory.getLogger(ProfileDistiller.class);

    private static final String PROMPT = """
            你在维护一个个人助理的用户画像 Wiki，并从对话中提取实体关系。
            规则：
            - 只记录用户明确表达的偏好（口味/忌口、常用地址、预算、回复风格等），拿不准的不记录；
            - 健康、政治、宗教等敏感主题一律不记录；
            - 已有页面已覆盖的信息不要重复输出；
            - 更新已有页面时沿用其 slug，content 为整页重写后的完整内容（保留原有有效信息并融入新信息）；
            - 新页面 slug 用简短中文名；scope 默认 global，明确属于某业务领域时填领域名（如 mcd-ordering）；
            - triples 提取对话中出现的实体关系（如 用户-忌口-花生、用户-常用地址-某小区），没有则输出空数组；
            - 没有可记录内容时输出 {"pages":[],"triples":[]}；
            - 只输出严格 JSON，不要输出任何其他内容，格式：
              {"pages":[{"slug":"","title":"","scope":"global","content":"","confidence":0.9}],"triples":[{"subject":"","predicate":"","object":""}]}
            现有页面（slug | scope | title | content）：
            """;

    private final Model fastModel;
    private final WikiStore wikiStore;
    private final EntityStore entityStore;
    private final ObjectMapper objectMapper;
    private final MemoryTaskExecutor taskExecutor;
    private final boolean enabled;

    public ProfileDistiller(@Qualifier("dashScopeFastModel") Model fastModel,
                            WikiStore wikiStore,
                            EntityStore entityStore,
                            ObjectMapper objectMapper,
                            MemoryTaskExecutor taskExecutor,
                            @Value("${app.memory.distiller.enabled:true}") boolean enabled) {
        this.fastModel = fastModel;
        this.wikiStore = wikiStore;
        this.entityStore = entityStore;
        this.objectMapper = objectMapper;
        this.taskExecutor = taskExecutor;
        this.enabled = enabled;
    }

    /** 异步提炼一条用户消息；开关关闭或文本为空时直接跳过。 */
    public void distill(String userText) {
        if (!enabled || !StringUtils.hasText(userText)) {
            return;
        }
        taskExecutor.execute(() -> {
            try {
                doDistill(userText);
            } catch (Exception e) {
                log.warn("画像提炼失败（fail-open，忽略）：{}", e.getMessage());
            }
        });
    }

    private void doDistill(String userText) throws Exception {
        StringBuilder pages = new StringBuilder();
        for (WikiPage page : wikiStore.pages()) {
            pages.append(page.slug()).append(" | ").append(page.scope()).append(" | ")
                    .append(page.title()).append(" | ").append(page.content().trim()).append('\n');
        }
        String prompt = PROMPT + (pages.isEmpty() ? "（空）" : pages.toString())
                + "\n用户的话：\n" + userText;

        var responses = fastModel.stream(List.of(new UserMessage(prompt)), null,
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
        String json = extractJson(text.toString());
        if (json == null) {
            log.warn("画像提炼输出不是 JSON 对象，忽略：{}", text);
            return;
        }
        DistillResult result = objectMapper.readValue(json, DistillResult.class);
        applyPages(result.pages());
        applyTriples(result.triples());
    }

    private void applyPages(List<PageOp> ops) {
        if (ops == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (PageOp op : ops) {
            if (op == null || !StringUtils.hasText(op.title()) || !StringUtils.hasText(op.content())) {
                continue;
            }
            String slug = StringUtils.hasText(op.slug()) ? op.slug().trim() : op.title().trim();
            String scope = StringUtils.hasText(op.scope()) ? op.scope().trim() : WikiStore.SCOPE_GLOBAL;
            double confidence = op.confidence() == null ? 0.8
                    : Math.max(0.0, Math.min(1.0, op.confidence()));
            wikiStore.savePage(new WikiPage(WikiStore.sanitize(slug), op.title().trim(), scope,
                    WikiStore.SOURCE_INFERRED, confidence, now, op.content().trim()));
            log.info("画像 Wiki 页新增/更新：{}（{}）", op.title(), scope);
        }
    }

    private void applyTriples(List<TripleOp> ops) {
        if (ops == null || ops.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<EntityStore.Triple> triples = ops.stream()
                .filter(op -> op != null && StringUtils.hasText(op.subject())
                        && StringUtils.hasText(op.predicate()) && StringUtils.hasText(op.object()))
                .map(op -> new EntityStore.Triple(op.subject().trim(), op.predicate().trim(),
                        op.object().trim(), now, null, now))
                .toList();
        entityStore.append(triples);
        if (!triples.isEmpty()) {
            log.info("实体簿新增 {} 条三元组", triples.size());
        }
    }

    /** 容错截取模型输出中的 JSON 对象部分。 */
    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : null;
    }

    /** 提炼输出结构（宽松解析，字段可缺省）。 */
    public record DistillResult(List<PageOp> pages, List<TripleOp> triples) {
    }

    public record PageOp(String slug, String title, String scope, String content, Double confidence) {
    }

    public record TripleOp(String subject, String predicate, String object) {
    }
}
