package org.celanwang.mio.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashSet;
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
 */
@Component
public class ProfileInjector {

    private static final Logger log = LoggerFactory.getLogger(ProfileInjector.class);

    /**
     * 领域别名表：消息命中别名即视为该领域相关。
     * 起步阶段内置硬编码；多场景后应由领域适配器/SKILL 提供（见记忆架构设计）。
     */
    private static final Map<String, Set<String>> DOMAIN_ALIASES = Map.of(
            "mcd-ordering", Set.of("麦当劳", "巨无霸", "汉堡", "薯条", "门店", "套餐", "优惠券", "麦乐送"));

    private final WikiStore wikiStore;
    private final boolean filterEnabled;
    private final int maxEntries;

    public ProfileInjector(WikiStore wikiStore,
                           @Value("${app.memory.inject.filter-enabled:true}") boolean filterEnabled,
                           @Value("${app.memory.inject.max-entries:30}") int maxEntries) {
        this.wikiStore = wikiStore;
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
        List<WikiPage> selected = all.stream()
                .filter(page -> WikiStore.SCOPE_GLOBAL.equals(page.scope())
                        || relevant(page, currentUserText))
                .sorted((a, b) -> {
                    int byConfidence = Double.compare(b.confidence(), a.confidence());
                    return byConfidence != 0 ? byConfidence
                            : Long.compare(b.updatedAt(), a.updatedAt());
                })
                .limit(Math.max(maxEntries, 1))
                .toList();
        if (selected.size() < all.size()) {
            log.info("画像注入过滤：{} 页候选 → 注入 {} 页", all.size(), selected.size());
        }
        return wikiStore.render(selected);
    }

    /** domain 页相关性：领域别名命中，或页面内容与消息存在词面重叠。 */
    private boolean relevant(WikiPage page, String message) {
        Set<String> aliases = DOMAIN_ALIASES.getOrDefault(page.scope(), Set.of());
        if (message.contains(page.scope()) || aliases.stream().anyMatch(message::contains)) {
            return true;
        }
        String pageText = page.title() + page.content();
        return tokens(message).stream().anyMatch(pageText::contains);
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
