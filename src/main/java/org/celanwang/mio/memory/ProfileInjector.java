package org.celanwang.mio.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.celanwang.mio.config.DomainAliasRegistry;
import org.celanwang.mio.trace.TraceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 画像注入器：按当前用户消息的相关性过滤画像 Wiki 页后渲染注入文本。三级判定：
 * 1. 规则（确定性，零成本）：global 页全量保留；domain 页命中领域别名或词面重叠即保留；
 * 2. 向量召回：规则未命中的 domain 页与消息做余弦相似度，不低于阈值即保留
 *    （页面向量异步懒建索引、仅存内存，embedding 不可用时静默降级）；
 * 3. 轻量模型复核：仍未命中的页批量交轻量模型二次判定（3 秒超时、结果按消息缓存，
 *    超时/异常/输出非法一律倒向不注入——画像缺失只是不贴心，不阻断对话）。
 * 超过 maxEntries 时按置信度 × 新近度截断。开关关闭或消息为空时回退为全量注入。
 * 每次过滤决策写入事件日志（inject_decision），供离线评估过滤准确率。
 */
@Component
public class ProfileInjector {

    private static final Logger log = LoggerFactory.getLogger(ProfileInjector.class);

    private static final String JUDGE_PROMPT = """
            判断用户消息与下列画像页是否相关。相关 = 回应这条消息时，知道该页信息会改变或丰富回答\
            （例如消息涉及该页所属领域但未出现具体关键词，或需求与该页记录的偏好有关）。拿不准一律判不相关。
            只输出严格 JSON：{"relevant":["slug1","slug2"]}；都不相关输出 {"relevant":[]}。不要输出任何其他内容。
            画像页（slug | 标题 | 内容）：
            """;
    private static final int JUDGE_TIMEOUT_SECONDS = 3;
    private static final int JUDGE_CACHE_MAX = 200;

    private final WikiStore wikiStore;
    private final DomainAliasRegistry aliasRegistry;
    private final EventPublisher eventPublisher;
    private final Model fastModel;
    private final ObjectMapper objectMapper;
    private final EmbeddingClient embeddingClient;
    private final MemoryTaskExecutor taskExecutor;
    private final boolean filterEnabled;
    private final boolean llmFilterEnabled;
    private final boolean vectorEnabled;
    private final double vectorMinScore;
    private final int maxEntries;
    /** 模型复核结果缓存：消息 → 判定相关的 slug 集合（有界 LRU，重复追问不重复调模型）。 */
    private final Map<String, Set<String>> judgeCache =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Set<String>> eldest) {
                    return size() > JUDGE_CACHE_MAX;
                }
            });
    /** 页面内存向量索引（slug → 向量）；页面量级为十级，暴力余弦即可，不持久化。 */
    private final Map<String, float[]> pageVectors = new ConcurrentHashMap<>();
    /** 已索引页面的内容指纹（slug → hash），内容变更时触发重 embed。 */
    private final Map<String, String> pageVectorHashes = new ConcurrentHashMap<>();

    public ProfileInjector(WikiStore wikiStore,
                           DomainAliasRegistry aliasRegistry,
                           EventPublisher eventPublisher,
                           @Qualifier("dashScopeFastModel") Model fastModel,
                           ObjectMapper objectMapper,
                           EmbeddingClient embeddingClient,
                           MemoryTaskExecutor taskExecutor,
                           @Value("${app.memory.inject.filter-enabled:true}") boolean filterEnabled,
                           @Value("${app.memory.inject.llm-filter-enabled:true}") boolean llmFilterEnabled,
                           @Value("${app.memory.inject.vector-enabled:true}") boolean vectorEnabled,
                           @Value("${app.memory.inject.vector-min-score:0.35}") double vectorMinScore,
                           @Value("${app.memory.inject.max-entries:30}") int maxEntries) {
        this.wikiStore = wikiStore;
        this.aliasRegistry = aliasRegistry;
        this.eventPublisher = eventPublisher;
        this.fastModel = fastModel;
        this.objectMapper = objectMapper;
        this.embeddingClient = embeddingClient;
        this.taskExecutor = taskExecutor;
        this.filterEnabled = filterEnabled;
        this.llmFilterEnabled = llmFilterEnabled;
        this.vectorEnabled = vectorEnabled;
        this.vectorMinScore = vectorMinScore;
        this.maxEntries = maxEntries;
    }

    /** 渲染注入文本；currentUserText 为当前用户消息（空则全量注入），queryVector 为注入链路的共享查询向量。 */
    public String render(String currentUserText, Supplier<float[]> queryVector) {
        List<WikiPage> all = wikiStore.pages();
        if (all.isEmpty()) {
            return "";
        }
        if (!filterEnabled || !StringUtils.hasText(currentUserText)) {
            return wikiStore.render(all);
        }
        List<WikiPage> selected = new ArrayList<>();
        Map<String, String> reasonBySlug = new LinkedHashMap<>();
        List<WikiPage> undecided = new ArrayList<>();
        for (WikiPage page : all) {
            if (WikiStore.SCOPE_GLOBAL.equals(page.scope())) {
                selected.add(page);
                reasonBySlug.put(page.slug(), "global");
                continue;
            }
            String hit = ruleHit(page, currentUserText);
            if (hit != null) {
                selected.add(page);
                reasonBySlug.put(page.slug(), hit);
            } else {
                undecided.add(page);
            }
        }
        if (vectorEnabled && !undecided.isEmpty()) {
            indexPagesAsync(undecided);
            float[] sharedVector = queryVector.get();
            if (sharedVector != null) {
                undecided.removeIf(page -> {
                    float[] vector = pageVectors.get(page.slug());
                    if (vector == null) {
                        return false;
                    }
                    double score = cosine(sharedVector, vector);
                    if (score >= vectorMinScore) {
                        selected.add(page);
                        reasonBySlug.put(page.slug(),
                                "vector " + String.format("%.2f", score));
                        return true;
                    }
                    return false;
                });
            }
        }
        int judged = 0;
        if (llmFilterEnabled && !undecided.isEmpty()) {
            judged = undecided.size();
            Set<String> hits = judgeWithModel(currentUserText, undecided);
            undecided.removeIf(page -> {
                if (hits.contains(page.slug())) {
                    selected.add(page);
                    reasonBySlug.put(page.slug(), "llm");
                    return true;
                }
                return false;
            });
        }
        List<WikiPage> finalSelection = selected.stream()
                .sorted((a, b) -> {
                    int byConfidence = Double.compare(b.confidence(), a.confidence());
                    return byConfidence != 0 ? byConfidence
                            : Long.compare(b.updatedAt(), a.updatedAt());
                })
                .limit(Math.max(maxEntries, 1))
                .toList();
        publishDecision(currentUserText, all.size(), finalSelection, reasonBySlug, judged);
        return wikiStore.render(finalSelection);
    }

    /**
     * 页面向量懒索引：内容指纹变化的页异步 embed 入内存索引。
     * 索引未就绪期间向量级静默落空（规则与模型复核仍生效）；embed 失败不记指纹，下次 render 重试。
     */
    private void indexPagesAsync(List<WikiPage> pages) {
        List<WikiPage> stale = new ArrayList<>();
        for (WikiPage page : pages) {
            if (!String.valueOf(embeddingText(page).hashCode())
                    .equals(pageVectorHashes.get(page.slug()))) {
                stale.add(page);
            }
        }
        if (stale.isEmpty()) {
            return;
        }
        taskExecutor.execute(() -> {
            try {
                List<String> texts = stale.stream().map(this::embeddingText).toList();
                Optional<List<float[]>> embedded = embeddingClient.embed(texts);
                if (embedded.isEmpty()) {
                    return;
                }
                List<float[]> vectors = embedded.get();
                for (int i = 0; i < stale.size(); i++) {
                    pageVectors.put(stale.get(i).slug(), vectors.get(i));
                    pageVectorHashes.put(stale.get(i).slug(), String.valueOf(texts.get(i).hashCode()));
                }
            } catch (Exception e) {
                log.warn("画像页向量索引失败（fail-open）：{}", e.getMessage());
            }
        });
    }

    private String embeddingText(WikiPage page) {
        return page.title() + "\n" + page.content().trim();
    }

    private double cosine(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        int length = Math.min(a.length, b.length);
        for (int i = 0; i < length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        return normA == 0 || normB == 0 ? 0 : dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /** 过滤决策留痕：写入事件日志（不经 TraceStore，注入无会话归属，不占用面板会话列表）。 */
    private void publishDecision(String message, int candidates, List<WikiPage> injected,
                                 Map<String, String> reasonBySlug, int judged) {
        try {
            StringBuilder hits = new StringBuilder();
            for (WikiPage page : injected) {
                if (!hits.isEmpty()) {
                    hits.append(", ");
                }
                hits.append(page.slug()).append('=').append(reasonBySlug.get(page.slug()));
            }
            eventPublisher.publish("memory", new TraceEvent(System.currentTimeMillis(),
                    "memory:inject", "inject_decision",
                    "画像注入过滤：" + candidates + " 页候选 → 注入 " + injected.size() + " 页",
                    "命中 [" + hits + "]；模型复核 " + judged + " 页；消息：" + truncate(message, 100)));
        } catch (Exception e) {
            log.warn("注入决策留痕失败（fail-open）：{}", e.getMessage());
        }
    }

    /**
     * 轻量模型复核：规则未命中的 domain 页批量判定相关性。
     * 超时/异常/输出非法一律返回空集（倒向不注入）；只接受候选页内的 slug，防模型编造。
     */
    private Set<String> judgeWithModel(String message, List<WikiPage> undecided) {
        Set<String> cached = judgeCache.get(message);
        if (cached != null) {
            return cached;
        }
        try {
            StringBuilder pages = new StringBuilder();
            for (WikiPage page : undecided) {
                pages.append(page.slug()).append(" | ").append(page.title()).append(" | ")
                        .append(page.content().trim()).append('\n');
            }
            var responses = fastModel.stream(
                            List.of(new UserMessage(JUDGE_PROMPT + pages + "\n用户消息：\n" + message)),
                            null, GenerateOptions.builder().build())
                    .collectList().block(Duration.ofSeconds(JUDGE_TIMEOUT_SECONDS));
            if (responses == null || responses.isEmpty()) {
                return Set.of();
            }
            StringBuilder text = new StringBuilder();
            for (var block : responses.get(responses.size() - 1).getContent()) {
                if (block instanceof TextBlock textBlock) {
                    text.append(textBlock.getText());
                }
            }
            String json = extractJson(text.toString());
            if (json == null) {
                log.warn("画像相关性复核输出不是 JSON，忽略：{}", text);
                return Set.of();
            }
            Set<String> validSlugs = new HashSet<>();
            for (WikiPage page : undecided) {
                validSlugs.add(page.slug());
            }
            Set<String> hits = new HashSet<>();
            for (JsonNode node : objectMapper.readTree(json).path("relevant")) {
                String slug = node.asText();
                if (validSlugs.contains(slug)) {
                    hits.add(slug);
                }
            }
            judgeCache.put(message, hits);
            return hits;
        } catch (Exception e) {
            log.warn("画像相关性复核失败（fail-open，倒向不注入）：{}", e.getMessage());
            return Set.of();
        }
    }

    /** 容错截取模型输出中的 JSON 对象部分。 */
    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : null;
    }

    /** domain 页规则命中：领域别名命中返回 "alias"，词面重叠命中返回 "overlap"，未命中返回 null。 */
    private String ruleHit(WikiPage page, String message) {
        Set<String> aliases = aliasRegistry.aliasesOf(page.scope());
        if (message.contains(page.scope()) || aliases.stream().anyMatch(message::contains)) {
            return "alias";
        }
        String pageText = page.title() + page.content();
        return tokens(message).stream().anyMatch(pageText::contains) ? "overlap" : null;
    }

    private String truncate(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /** 提取匹配用 token：连续 CJK 字符的 2-gram + 长度 ≥3 的 ASCII 小写词。 */
    private Set<String> tokens(String text) {
        Set<String> tokens = new HashSet<>();
        StringBuilder cjk = new StringBuilder();
        StringBuilder ascii = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                cjk.append(c);
                flushAscii(ascii, tokens);
            } else if (Character.isLetterOrDigit(c) && c < 0x80) {
                ascii.append(Character.toLowerCase(c));
                flushCjk(cjk, tokens);
            } else {
                flushCjk(cjk, tokens);
                flushAscii(ascii, tokens);
            }
        }
        flushCjk(cjk, tokens);
        flushAscii(ascii, tokens);
        return tokens;
    }

    private void flushCjk(StringBuilder cjk, Set<String> tokens) {
        for (int i = 0; i + 2 <= cjk.length(); i++) {
            tokens.add(cjk.substring(i, i + 2));
        }
        cjk.setLength(0);
    }

    private void flushAscii(StringBuilder ascii, Set<String> tokens) {
        if (ascii.length() >= 3) {
            tokens.add(ascii.toString());
        }
        ascii.setLength(0);
    }
}
