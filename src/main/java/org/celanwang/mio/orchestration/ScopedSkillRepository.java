package org.celanwang.mio.orchestration;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.*;
import java.util.List;

/** A worker cannot enumerate unrelated skill instructions through the built-in skill tools. */
final class ScopedSkillRepository implements AgentSkillRepository {
    private final AgentSkillRepository delegate;private final String skill;
    ScopedSkillRepository(AgentSkillRepository delegate,String skill){this.delegate=delegate;this.skill=skill;}
    @Override public AgentSkill getSkill(String name){if(!skill.equals(name))throw new IllegalArgumentException("技能不属于当前领域");return delegate.getSkill(name);}
    @Override public List<String> getAllSkillNames(){return List.of(skill);}
    @Override public List<AgentSkill> getAllSkills(){return List.of(getSkill(skill));}
    @Override public boolean save(List<AgentSkill> skills,boolean overwrite){throw new UnsupportedOperationException("只读技能");}
    @Override public boolean delete(String name){throw new UnsupportedOperationException("只读技能");}
    @Override public boolean skillExists(String name){return skill.equals(name)&&delegate.skillExists(name);}
    @Override public AgentSkillRepositoryInfo getRepositoryInfo(){return delegate.getRepositoryInfo();}
    @Override public String getSource(){return delegate.getSource();}
    @Override public void setWriteable(boolean enabled){if(enabled)throw new UnsupportedOperationException("只读技能");}
    @Override public boolean isWriteable(){return false;}
}
