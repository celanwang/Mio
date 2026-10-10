package org.celanwang.mio.config;

import org.celanwang.mio.memory.JsonFileSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 领域别名注册表：启动时扫描技能目录，解析各 SKILL.md frontmatter 中的 aliases 字段，
 * 供画像注入做领域相关性判定。别名随技能包版本化，接入新业务领域无需改动内核。
 * 仅支持内联列表写法：aliases: [甲, 乙]；单个技能解析失败静默跳过。
 */
@Component
public class DomainAliasRegistry {

    private static final Logger log = LoggerFactory.getLogger(DomainAliasRegistry.class);

    private final Map<String, Set<String>> aliasesByDomain;

    public DomainAliasRegistry(@Value("${app.agent.skills-dir:./skills}") String skillsDir) {
        this.aliasesByDomain = scan(JsonFileSupport.expandHome(skillsDir));
        log.info("领域别名已加载：{}", aliasesByDomain.keySet());
    }

    /** 某领域的别名集合；未知领域返回空集。 */
    public Set<String> aliasesOf(String domain) {
        return aliasesByDomain.getOrDefault(domain, Set.of());
    }

    private Map<String, Set<String>> scan(Path skillsDir) {
        Map<String, Set<String>> result = new HashMap<>();
        if (!Files.isDirectory(skillsDir)) {
            return result;
        }
        try (var stream = Files.list(skillsDir)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                Path skillFile = dir.resolve("SKILL.md");
                if (Files.exists(skillFile)) {
                    parseSkill(skillFile, result);
                }
            }
        } catch (Exception e) {
            log.warn("技能目录扫描失败（领域别名为空）：{}", e.getMessage());
        }
        return result;
    }

    /** 解析单个 SKILL.md frontmatter 中的 name 与 aliases，写入注册表。 */
    private void parseSkill(Path skillFile, Map<String, Set<String>> out) {
        try {
            List<String> lines = Files.readAllLines(skillFile);
            if (lines.isEmpty() || !lines.get(0).trim().equals("---")) {
                return;
            }
            String name = null;
            Set<String> aliases = Set.of();
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.equals("---")) {
                    break;
                }
                if (line.startsWith("name:")) {
                    name = line.substring("name:".length()).trim();
                } else if (line.startsWith("aliases:")) {
                    aliases = parseInlineList(line.substring("aliases:".length()).trim());
                }
            }
            if (name != null && !name.isEmpty() && !aliases.isEmpty()) {
                out.put(name, aliases);
            }
        } catch (Exception e) {
            log.warn("解析技能别名失败（跳过 {}）：{}", skillFile, e.getMessage());
        }
    }

    /** 解析内联列表：[甲, 乙, 丙] → 集合。 */
    private Set<String> parseInlineList(String value) {
        Set<String> aliases = new HashSet<>();
        for (String item : value.replace("[", "").replace("]", "").split("[,，]")) {
            String alias = item.trim();
            if (!alias.isEmpty()) {
                aliases.add(alias);
            }
        }
        return aliases;
    }
}
