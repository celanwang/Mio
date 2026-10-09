package org.celanwang.mio.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 信任规则统计（trust.json）：记录人工对工具调用的批准/拒绝次数。
 * 失败方向 fail-closed：文件损坏视为空表并记 error，绝不据此放行任何操作。
 */
@Component
public class TrustStore {

    private static final Logger log = LoggerFactory.getLogger(TrustStore.class);

    private final Path file;
    private final ObjectMapper objectMapper;
    private List<TrustRule> rules;

    public TrustStore(@Value("${app.memory.dir:./.mio}") String dir, ObjectMapper objectMapper) {
        this.file = JsonFileSupport.expandHome(dir).resolve("trust.json");
        this.objectMapper = objectMapper;
    }

    /** 记录一次人工确认结果；只有人工确认才计入统计，Jev 自动放行不算用户授权。 */
    public synchronized void record(String toolName, Map<String, Object> input, boolean approved) {
        try {
            String signature = signature(toolName, input);
            List<TrustRule> all = new ArrayList<>(ensureLoaded());
            TrustRule updated = null;
            for (int i = 0; i < all.size(); i++) {
                TrustRule old = all.get(i);
                if (old.toolName().equals(toolName) && old.paramSignature().equals(signature)) {
                    updated = new TrustRule(toolName, signature,
                            old.approvals() + (approved ? 1 : 0),
                            old.rejections() + (approved ? 0 : 1),
                            System.currentTimeMillis());
                    all.set(i, updated);
                    break;
                }
            }
            if (updated == null) {
                all.add(new TrustRule(toolName, signature, approved ? 1 : 0, approved ? 0 : 1,
                        System.currentTimeMillis()));
            }
            rules = all;
            save();
        } catch (Exception e) {
            log.error("信任统计记录失败（fail-closed，不影响本次结果）：{}", e.getMessage());
        }
    }

    public synchronized List<TrustRule> rules() {
        return List.copyOf(ensureLoaded());
    }

    /** 重置某工具的全部信任统计。 */
    public synchronized void reset(String toolName) {
        List<TrustRule> all = ensureLoaded();
        if (all.removeIf(rule -> rule.toolName().equals(toolName))) {
            save();
        }
    }

    /** 参数骨架：工具名 + 排序后的参数 key 列表（不含值）。 */
    static String signature(String toolName, Map<String, Object> input) {
        String keys = input == null ? "" : String.join(",", new TreeSet<>(input.keySet()));
        return toolName + "[" + keys + "]";
    }

    private List<TrustRule> ensureLoaded() {
        if (rules != null) {
            return rules;
        }
        rules = new ArrayList<>();
        try {
            String json = JsonFileSupport.read(file);
            if (json == null) {
                return rules;
            }
            TrustFile parsed = objectMapper.readValue(json, TrustFile.class);
            if (parsed.rules() != null) {
                rules.addAll(parsed.rules());
            }
        } catch (Exception e) {
            log.error("信任文件损坏，按空表处理（fail-closed）：{}（{}）", file, e.getMessage());
        }
        return rules;
    }

    private void save() {
        try {
            JsonFileSupport.writeAtomic(file,
                    objectMapper.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(new TrustFile(rules)));
        } catch (Exception e) {
            log.error("信任统计保存失败（fail-closed）：{}", e.getMessage());
        }
    }

    /** trust.json 的文件结构。 */
    public record TrustFile(List<TrustRule> rules) {
    }
}
