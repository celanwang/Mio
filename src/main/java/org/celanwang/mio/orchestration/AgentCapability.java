package org.celanwang.mio.orchestration;

import java.util.List;
import java.util.Objects;

/** Published by trusted application configuration, never by a planner or tool response. */
public record AgentCapability(String id, String name, String description, String skill, String version,
                              List<String> constraints,List<CapabilityInputContract> inputContracts) {
    public AgentCapability(String id,String name,String description,String skill,String version,List<String> constraints){
        this(id,name,description,skill,version,constraints,List.of());
    }
    public AgentCapability {
        if (id == null || !id.matches("[a-z][a-z0-9-]{0,63}")) throw new IllegalArgumentException("Invalid capability id");
        Objects.requireNonNull(name); Objects.requireNonNull(description);
        Objects.requireNonNull(skill); Objects.requireNonNull(version);
        constraints = List.copyOf(constraints);
        inputContracts=inputContracts==null?List.of():List.copyOf(inputContracts);
        if(inputContracts.stream().map(CapabilityInputContract::id).distinct().count()!=inputContracts.size()
                ||!inputContracts.isEmpty()&&inputContracts.stream().noneMatch(c->c.id().equals("default")))throw new IllegalArgumentException("能力输入契约重复或缺少默认契约");
    }
    public String executionVersion(){return inputContracts.isEmpty()?version:JsonFingerprint.hash(java.util.Map.of("skill",version,"contracts",inputContracts));}
    public void validateSpec(TaskSpec spec){
        if(inputContracts.isEmpty()){
            if(!spec.inputContract().equals("default"))throw new IllegalArgumentException("能力未注册该输入契约");
            return;
        }
        contract(spec).validateSpec(spec);
    }
    public void validateInputs(TaskSpec spec,List<TaskFacts.BoundInput> inputs){
        validateSpec(spec);
        if(!inputContracts.isEmpty())contract(spec).validate(inputs);
    }
    private CapabilityInputContract contract(TaskSpec spec){return inputContracts.stream().filter(c->c.id().equals(spec.inputContract())).findFirst()
            .orElseThrow(()->new IllegalArgumentException("能力未注册该输入契约"));}
}
