package org.celanwang.mio.orchestration;

import java.util.List;

public record TaskSpec(String key, String capabilityId, String objective, List<String> constraints,
                       List<String> dependencies, TaskScope scope,List<InputBinding> inputs,String inputContract) {
    public TaskSpec(String key,String capabilityId,String objective,List<String> constraints,List<String> dependencies,TaskScope scope,List<InputBinding> inputs){
        this(key,capabilityId,objective,constraints,dependencies,scope,inputs,"default");
    }
    public TaskSpec(String key,String capabilityId,String objective,List<String> constraints,List<String> dependencies,TaskScope scope){
        this(key,capabilityId,objective,constraints,dependencies,scope,List.of());
    }
    public TaskSpec {
        if (key == null || !key.matches("[a-zA-Z0-9_-]{1,80}")) throw new IllegalArgumentException("任务 key 无效");
        if (objective == null || objective.isBlank() || objective.length() > 6000) throw new IllegalArgumentException("任务目标应为 1–6000 字符");
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        if (constraints.size() > 20 || constraints.stream().anyMatch(v -> v == null || v.length() > 1000))
            throw new IllegalArgumentException("任务约束过多或过长");
        if (dependencies.size() > 8 || dependencies.stream().anyMatch(key::equals)
                || dependencies.stream().distinct().count() != dependencies.size()) throw new IllegalArgumentException("任务依赖无效");
        scope = scope == null ? TaskScope.PREPARE : scope;
        inputContract=inputContract==null?"default":inputContract;
        if(!inputContract.matches("[a-z][a-z0-9-]{0,63}"))throw new IllegalArgumentException("输入契约编号无效");
        inputs=inputs==null?List.of():List.copyOf(inputs);
        if(inputs.size()>8||inputs.stream().map(InputBinding::alias).distinct().count()!=inputs.size())throw new IllegalArgumentException("事实输入过多或别名重复");
        for(var binding:inputs)if(!dependencies.contains(binding.taskId()))throw new IllegalArgumentException("事实输入必须引用已声明的依赖任务");
    }
}
