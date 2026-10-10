package org.celanwang.mio.memory;

import org.celanwang.mio.config.DomainAliasRegistry;
import org.celanwang.mio.trace.TraceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 画像注入器：按当前用户消息的相关性过滤画像 Wiki 页后渲染注入文本。
 * 规则（确定性，不用 LLM/embedding）：
 * 1. global 页全量保留（跨场景恒真的基本盘）；
 * 2. domain 页仅当命中领域别名，或页标题/正文与消息有词面重叠时保留；
 * 3. 超过 maxEntries 时按置信度 × 新近度截断。
 * 开关关闭或消息为空时回退为全量注入。
 * 每次过滤决策写入事件日志（inject_decision），供离线评估过滤准确率。
 */
@Component
public class ProfileInjector {

    private static final Logger log = LoggerFactory.getLogger(ProfileInjector.class);

    private final WikiStore wikiStore;
    private final DomainAliasRegistry aliasRegistry;
    private final EventPublisher eventPublisher;
    private final boolean filterEnabled;
    private final int maxEntries;

    public ProfileInjector(WikiStore wikiStore,
                           DomainAliasRegistry aliasRegistry,
                           EventPublisher eventPublisher,
                           @Value("${app.memory.inject.filter-enabled:true}") boolean filterEnabled,
                           @Value("${app.memory.inject.max-entries:30}") int maxEntries) {
        this.wikiStore = wikiStore;
        this.aliasRegistry = aliasRegistry;
        this.eventPublisher = eventPublisher;
        this.filterEnabled = filterEnabled;
        this.maxEntries = maxEntries;
    }

    /** 渲染注入文本；currentUserText 为当前用户消息，可为空（空则全量注入）。 */
    public String render(String currentUserText) {
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
        List<WikiPage> finalSelection = selected.stream()
                .sorted((a, b) -> {
                    int byConfidence = Double.compare(b.confidence(), a.confidence());
                    return byConfidence != 0 ? byConfidence
                            : Long.compare(b.updatedAt(), a.updatedAt());
                })
                .limit(Math.max(maxEntries, 1))
                .toList();
        publishDecision(currentUserText, all.size(), finalSelection, reasonBySlug);
        return wikiStore.render(finalSelection);
    }

    /** 过滤决策留痕：写入事件日志（不经 TraceStore，注入无会话归属，不占用面板会话列表）。 */
    private void publishDecision(String message, int candidates, List<WikiPage> injected,
                                 Map<String, String> reasonBySlug) {
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
                    "命中 [" + hits + "]；消息：" + truncate(message, 100)));
        } catch (Exception e) {
            log.warn("注入决策留痕失败（fail-open）：{}", e.getMessage());
        }
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
