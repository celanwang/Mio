package org.celanwang.mio.orchestration;

import java.util.*;

/** Trusted, versioned input requirements. Selecting a contract never grants business authority. */
public record CapabilityInputContract(String id,String description,Map<String,String> requiredFacts,boolean preparationOnly) {
    public CapabilityInputContract {
        if(id==null||!id.matches("[a-z][a-z0-9-]{0,63}"))throw new IllegalArgumentException("输入契约编号无效");
        if(description==null||description.isBlank())throw new IllegalArgumentException("输入契约说明缺失");
        requiredFacts=Map.copyOf(requiredFacts);
        if(requiredFacts.size()>8||requiredFacts.entrySet().stream().anyMatch(e->!e.getKey().matches("[a-z][a-zA-Z0-9_]{0,63}")||!e.getValue().matches("[a-z][a-z0-9_]{0,63}")))
            throw new IllegalArgumentException("输入契约字段无效");
    }
    public void validateSpec(TaskSpec spec){
        if(preparationOnly&&spec.scope()!=TaskScope.PREPARE)throw new IllegalArgumentException("本输入契约仅用于查询与分析");
        var aliases=spec.inputs().stream().map(InputBinding::alias).collect(java.util.stream.Collectors.toSet());
        if(!aliases.equals(requiredFacts.keySet()))throw new IllegalArgumentException("必须完整绑定输入契约要求的事实别名："+requiredFacts.keySet());
    }
    public void validate(List<TaskFacts.BoundInput> inputs){
        if(inputs.size()!=requiredFacts.size()||inputs.stream().anyMatch(i->!Objects.equals(requiredFacts.get(i.alias()),i.fact().type())))
            throw new TaskFacts.InputUnavailable("事实类型与能力输入契约不匹配");
    }
    public static List<CapabilityInputContract> contextual(){return List.of(
            new CapabilityInputContract("default","独立领域任务，不接收跨任务事实",Map.of(),false),
            new CapabilityInputContract("ride-quote-context","基于可信短时报价分析时间或预算；不能跨子会话用于下单",Map.of("rideQuote","ride_quote"),true),
            new CapabilityInputContract("resource-context","基于已核实资源编号查询或分析；编号不证明当前业务状态",Map.of("resource","provider_resource_reference"),true));}
}
