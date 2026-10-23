package org.celanwang.mio.goals;

import org.celanwang.mio.orchestration.*;
import java.util.*;

/** User-confirmed preparation plan. Field revisions and step generations are independent of chat turns. */
public record GoalProgram(String goalId,long goalRevision,long revision,Map<String,Field> fields,List<Step> steps,
                          Policy policy,Map<String,Long> generations) {
    public record Field(String value,long revision){public Field{if(value==null||value.length()>2000||revision<1)throw new IllegalArgumentException("目标字段无效");}}
    public record Step(String id,String capabilityId,String objective,List<String> fields,List<String> dependencies,
                       String outputContract,List<GoalAcceptance.Check> checks,String inputContract,Map<String,String> inputs){
        public Step(String id,String capabilityId,String objective,List<String> fields,List<String> dependencies,String outputContract,List<GoalAcceptance.Check> checks){this(id,capabilityId,objective,fields,dependencies,outputContract,checks,"default",Map.of());}
        public Step{
            key(id);key(capabilityId);if(objective==null||objective.isBlank()||objective.length()>4000)throw new IllegalArgumentException("准备步骤需要明确目标");
            fields=fields==null?List.of():List.copyOf(fields);dependencies=dependencies==null?List.of():List.copyOf(dependencies);
            checks=checks==null?List.of():List.copyOf(checks);outputContract=outputContract==null?"report":outputContract;
            inputContract=inputContract==null?"default":inputContract;inputs=inputs==null?Map.of():Map.copyOf(inputs);if(inputs.size()>8)throw new IllegalArgumentException("事实输入过多");
            for(var ref:inputs.values()){var split=ref.split("/",2);if(split.length!=2||!dependencies.contains(split[0]))throw new IllegalArgumentException("事实引用应为 前置步骤编号/事实key");}
            if(fields.size()>20||dependencies.size()>8||checks.size()>8||new HashSet<>(dependencies).size()!=dependencies.size())throw new IllegalArgumentException("步骤依赖或条件过多");
        }
    }
    public record Policy(boolean enabled,long until,int maxActivations,int intervalSeconds,int parallelism,Set<String> capabilities,boolean trackResources){
        public Policy(boolean enabled,long until,int maxActivations,int intervalSeconds,int parallelism,Set<String> capabilities){this(enabled,until,maxActivations,intervalSeconds,parallelism,capabilities,false);}
        public Policy{
            capabilities=capabilities==null?Set.of():Set.copyOf(capabilities);
            if(until<0||maxActivations<1||maxActivations>10000||intervalSeconds<30||intervalSeconds>86400||parallelism<1||parallelism>4||capabilities.size()>20)
                throw new IllegalArgumentException("执行范围、期限或额度无效");
        }
    }
    public record Request(long expectedRevision,long expectedGoalRevision,Map<String,String> fields,List<Step> steps,Policy policy){}
    public GoalProgram{
        fields=Map.copyOf(fields);steps=List.copyOf(steps);generations=Map.copyOf(generations);
        if(fields.size()>20||steps.size()>12||goalRevision<1||revision<1||policy==null)throw new IllegalArgumentException("目标计划过大或缺少执行范围");
        fields.keySet().forEach(GoalProgram::key);var ids=new HashSet<String>();
        for(var step:steps){
            if(!ids.add(step.id())||!fields.keySet().containsAll(step.fields())||!policy.capabilities().contains(step.capabilityId()))throw new IllegalArgumentException("步骤引用了未确认的字段或能力");
        }
        for(var step:steps)if(!ids.containsAll(step.dependencies()))throw new IllegalArgumentException("步骤依赖不存在");
        for(var step:steps)visit(step.id(),steps,new HashSet<>(),new HashSet<>());
    }
    private static void visit(String id,List<Step> steps,Set<String> visiting,Set<String> done){
        if(done.contains(id))return;if(!visiting.add(id))throw new IllegalArgumentException("步骤依赖存在循环");
        var step=steps.stream().filter(s->s.id().equals(id)).findFirst().orElseThrow();for(String parent:step.dependencies())visit(parent,steps,visiting,done);visiting.remove(id);done.add(id);
    }
    public static void key(String value){if(value==null||!value.matches("[a-z][a-z0-9_-]{0,63}"))throw new IllegalArgumentException("编号必须以小写字母开头，最多64字符");}
    public String taskKey(Step step){return "goal_"+JsonFingerprint.hash(Map.of("goal",goalId)).substring(0,12)+"_"+step.id().substring(0,Math.min(40,step.id().length()))+"_"+generations.get(step.id());}
    public TaskSpec task(Step step,List<String> parents){
        String context=step.fields().stream().sorted().map(name->name+"="+fields.get(name).value()).collect(java.util.stream.Collectors.joining("\n"));
        return new TaskSpec(taskKey(step),step.capabilityId(),step.objective()+"\n用户确认的目标字段（准备数据，不批准业务写入）：\n"+context,List.of(),parents,TaskScope.PREPARE,List.of(),"default",TaskSpec.Dispatch.QUEUED,step.outputContract());
    }
    public TaskSpec task(Step step,Map<String,TaskRecord> parents){
        var base=task(step,step.dependencies().stream().map(id->parents.get(id).id()).toList());var bindings=new ArrayList<InputBinding>();
        step.inputs().forEach((alias,reference)->{var parts=reference.split("/",2);var parent=parents.get(parts[0]);var fact=TaskFacts.validAt(parent,System.currentTimeMillis()).stream().filter(f->f.key().equals(parts[1])).findFirst().orElseThrow(()->new IllegalStateException("所需事实不可用："+reference));bindings.add(new InputBinding(parent.id(),fact.id(),fact.version(),alias));});
        return new TaskSpec(base.key(),base.capabilityId(),base.objective(),base.constraints(),base.dependencies(),base.scope(),bindings,step.inputContract(),base.dispatch(),base.outputContract());
    }
}
