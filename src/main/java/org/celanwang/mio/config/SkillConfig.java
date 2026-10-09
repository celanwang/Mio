package org.celanwang.mio.config;

import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
public class SkillConfig {
    public static final String MCD_ORDERING_SKILL = "mcd-ordering";
    private static final Logger log = LoggerFactory.getLogger(SkillConfig.class);

    @Bean(destroyMethod = "close")
    public AgentSkillRepository agentSkillRepository() throws IOException {
        // 固定来源标识，使技能 ID 在 IDE 与打包运行时保持一致。
        var repository = new ClasspathSkillRepository("skills", "mio");
        try {
            if (!repository.skillExists(MCD_ORDERING_SKILL)) {
                throw new IllegalStateException("缺少麦当劳点餐技能，请检查 resources/skills/mcd-ordering/SKILL.md。");
            }
            log.info("已加载 Agent 技能：{}", repository.getAllSkillNames());
            return repository;
        } catch (RuntimeException error) {
            repository.close();
            throw error;
        }
    }
}
