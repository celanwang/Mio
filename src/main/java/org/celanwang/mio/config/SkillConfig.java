package org.celanwang.mio.config;

import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class SkillConfig {
    public static final String MCD_ORDERING_SKILL = "mcd-ordering";
    private static final Logger log = LoggerFactory.getLogger(SkillConfig.class);

    @Bean(destroyMethod = "close")
    public AgentSkillRepository agentSkillRepository(
            @Value("${app.agent.skills-dir:./skills}") String skillsDir) {
        // 技能放在文件系统目录（默认项目根 ./skills，可用 MIO_SKILLS_DIR 覆盖），
        // 修改技能无需重新构建；目录在 git 仓库内，仍随代码版本化。
        // 固定来源标识 "mio"，保持技能 ID 稳定。运行时只读使用，不写回该目录。
        var repository = new FileSystemSkillRepository(Path.of(skillsDir), false, "mio");
        try {
            if (!repository.skillExists(MCD_ORDERING_SKILL)) {
                throw new IllegalStateException(
                        "缺少麦当劳点餐技能，请检查 " + skillsDir + "/mcd-ordering/SKILL.md。");
            }
            log.info("已从 {} 加载 Agent 技能：{}", skillsDir, repository.getAllSkillNames());
            return repository;
        } catch (RuntimeException error) {
            repository.close();
            throw error;
        }
    }
}
