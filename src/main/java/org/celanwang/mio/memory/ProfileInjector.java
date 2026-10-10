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
 * 画像注入器：按当前用户消息的相关性过滤画像条目后渲染注入文本。
 * 规则（确定性，不用 LLM/embedding）：
 * 1. global 条目全量保留（跨场景恒真的基本盘）；
 * 2. domain 条目仅当命中领域别名，或条目 key/value 与消息有词面重叠时保留；
 * 3. 超过 maxEntries 时按置信度 × 新近度截断。
 * 开关关闭或消息为空时回退为全量注入（旧行为）。
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

    private final ProfileStore profileStore;
    private final boolean filterEnabled;
    private final int maxEntries;

    public ProfileInjector(ProfileStore profileStore,
                           @Value("${app.memory.inject.filter-enabled:true}") boolean filterEnabled,
                           @Value("${app.memory.inject.max-entries:30}") int maxEntries) {
        this.profileStore = profileStore;
        this.filterEnabled = filterEnabled;
        this.maxEntries = maxEntries;
    }

    /** 渲染注入文本；currentUserText 为当前用户消息，可为空（空则全量注入）。 */
    public String render(String currentUserText) {
        List<ProfileEntry> all = profileStore.entries();
        if (all.isEmpty()) {
            return "";
        }
        if (!filterEnabled || !StringUtils.hasText(currentUserText)) {
            return profileStore.renderEntries(all);
        }
        List<ProfileEntry> selected = all.stream()
                .filter(entry -> ProfileStore.SCOPE_GLOBAL.equals(entry.scope())
                        || relevant(entry, currentUserText))
                .sorted((a, b) -> {
                    int byConfidence = Double.compare(b.confidence(), a.confidence());
                    return byConfidence != 0 ? byConfidence
                            : Long.compare(b.updatedAt(), a.updatedAt());
                })
                .limit(Math.max(maxEntries, 1))
                .toList();
        if (selected.size() < all.size()) {
            log.info("画像注入过滤：{} 条候选 → 注入 {} 条", all.size(), selected.size());
        }
        return profileStore.renderEntries(selected);
    }

    /** domain 条目相关性：领域别名命中，或条目内容与消息存在词面重叠。 */
    private boolean relevant(ProfileEntry entry, String message) {
        Set<String> aliases = DOMAIN_ALIASES.getOrDefault(entry.scope(), Set.of());
        if (message.contains(entry.scope()) || aliases.stream().anyMatch(message::contains)) {
            return true;
        }
        String entryText = entry.key() + entry.value();
        return tokens(message).stream().anyMatch(entryText::contains);
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
