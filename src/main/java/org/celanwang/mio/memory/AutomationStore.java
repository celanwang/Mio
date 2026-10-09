package org.celanwang.mio.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 自动化规则存储（automations.json）：习惯提议 → 用户批准 → 生效。
 * 失败方向 fail-closed：文件损坏视为无规则、记 error、坏文件改名保留，绝不凭空放行自动化。
 */
@Component
public class AutomationStore {

    private static final Logger log = LoggerFactory.getLogger(AutomationStore.class);

    /** 本轮唯一支持的触发方式：会话开始时由 Agent 执行（不引入定时调度器）。 */
    public static final String TRIGGER_SESSION_START = "session-start";

    private final Path file;
    private final ObjectMapper objectMapper;
    private List<AutomationRule> rules;

    public AutomationStore(@Value("${app.memory.dir:./.mio}") String dir, ObjectMapper objectMapper) {
        this.file = JsonFileSupport.expandHome(dir).resolve("automations.json");
        this.objectMapper = objectMapper;
    }

    /** 幂等提议：同 toolName 已有任何状态的规则则不再生成。返回新建的规则，未新建返回 null。 */
    public synchronized AutomationRule propose(String toolName, String title, String reason) {
        List<AutomationRule> all = ensureLoaded();
        if (all.stream().anyMatch(rule -> rule.toolName().equals(toolName))) {
            return null;
        }
        AutomationRule rule = new AutomationRule(UUID.randomUUID().toString(), title, toolName,
                TRIGGER_SESSION_START, AutomationRule.Status.PROPOSED, reason,
                System.currentTimeMillis(), null);
        all.add(rule);
        save();
        return rule;
    }

    /** 批准：PROPOSED → ACTIVE。 */
    public synchronized AutomationRule approve(String id) {
        return decide(id, AutomationRule.Status.ACTIVE);
    }

    /** 拒绝：PROPOSED → REJECTED，不再重复提议。 */
    public synchronized AutomationRule reject(String id) {
        return decide(id, AutomationRule.Status.REJECTED);
    }

    /** 吊销：ACTIVE → REJECTED（保留记录，防止习惯检测立即再次提议）。 */
    public synchronized AutomationRule revoke(String id) {
        AutomationRule rule = find(id);
        if (rule == null || rule.status() != AutomationRule.Status.ACTIVE) {
            return null;
        }
        return decide(id, AutomationRule.Status.REJECTED);
    }

    public synchronized List<AutomationRule> rules() {
        return List.copyOf(ensureLoaded());
    }

    public synchronized List<AutomationRule> pending() {
        return ensureLoaded().stream()
                .filter(rule -> rule.status() == AutomationRule.Status.PROPOSED).toList();
    }

    public synchronized List<AutomationRule> active() {
        return ensureLoaded().stream()
                .filter(rule -> rule.status() == AutomationRule.Status.ACTIVE).toList();
    }

    private AutomationRule decide(String id, AutomationRule.Status status) {
        List<AutomationRule> all = ensureLoaded();
        for (int i = 0; i < all.size(); i++) {
            AutomationRule old = all.get(i);
            if (old.id().equals(id)) {
                AutomationRule updated = new AutomationRule(old.id(), old.title(), old.toolName(),
                        old.trigger(), status, old.reason(), old.createdAt(),
                        System.currentTimeMillis());
                all.set(i, updated);
                save();
                return updated;
            }
        }
        return null;
    }

    private AutomationRule find(String id) {
        return ensureLoaded().stream().filter(rule -> rule.id().equals(id)).findFirst().orElse(null);
    }

    private List<AutomationRule> ensureLoaded() {
        if (rules != null) {
            return rules;
        }
        rules = new ArrayList<>();
        try {
            String json = JsonFileSupport.read(file);
            if (json == null) {
                return rules;
            }
            AutomationFile parsed = objectMapper.readValue(json, AutomationFile.class);
            if (parsed.rules() != null) {
                rules.addAll(parsed.rules());
            }
        } catch (Exception e) {
            log.error("自动化规则文件损坏，按无规则处理（fail-closed）：{}（{}）", file, e.getMessage());
            quarantine();
        }
        return rules;
    }

    /** 损坏文件改名 .corrupted-<timestamp> 保留，便于人工排查。 */
    private void quarantine() {
        try {
            if (Files.exists(file)) {
                Files.move(file, file.resolveSibling(
                        file.getFileName() + ".corrupted-" + System.currentTimeMillis()),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.error("损坏自动化规则文件改名失败：{}", e.getMessage());
        }
    }

    private void save() {
        try {
            JsonFileSupport.writeAtomic(file,
                    objectMapper.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(new AutomationFile(rules)));
        } catch (Exception e) {
            log.error("自动化规则保存失败（fail-closed）：{}", e.getMessage());
        }
    }

    /** automations.json 的文件结构。 */
    public record AutomationFile(List<AutomationRule> rules) {
    }
}
