package org.celanwang.mio.orchestration;

import java.util.*;

/** Evidence projections. Only the latest observation of a key can be consumed. */
public final class TaskFacts {
    public static final class InputUnavailable extends IllegalStateException {
        private final TaskInvalidation invalidation;
        public InputUnavailable(String message){super(message);invalidation=null;}
        public InputUnavailable(TaskInvalidation invalidation){super(invalidation.message());this.invalidation=invalidation;}
        public TaskInvalidation invalidation(){return invalidation;}
    }
    public record BoundInput(String alias,TaskFact fact) {}
    public static List<TaskFact> of(TaskRecord task){
        if(task.invalidation()!=null)return List.of();
        List<TaskFact> result=new ArrayList<>();
        var latest=new LinkedHashMap<String,FactObservation>();
        for(var observation:task.observations())latest.put(observation.key(),observation);
        for(var observation:latest.values())if(observation.fact()!=null&&observation.fact().goalRevision()==task.goalRevision())result.add(observation.fact());
        for(var op:task.operations()){
            if(op.outcome()!=OperationOutcome.CONFIRMED_APPLIED||op.businessId().isBlank())continue;
            var receipts=op.receipts().stream().filter(r->r.outcome()==OperationOutcome.CONFIRMED_APPLIED&&r.businessId().equals(op.businessId())).toList();
            if(receipts.isEmpty())continue;
            var evidence=receipts.get(0);
            String id=op.id()+":business_id";
            String version=JsonFingerprint.hash(Map.of("id",id,"businessId",op.businessId(),"evidence",evidence.id()));
            result.add(new TaskFact(id,"operation.business_id","provider_resource_reference",Map.of("tool",op.tool(),"businessId",op.businessId()),
                    task.id(),evidence.id(),version,task.goalRevision(),evidence.observedAt(),Long.MAX_VALUE));
        }
        return List.copyOf(result);
    }
    public static List<TaskFact> validAt(TaskRecord task,long now){return of(task).stream().filter(f->f.observedAt()<=now&&now<f.expiresAt()).toList();}
    public static List<BoundInput> bind(CoordinationStore store,TaskRecord target,long now){
        TaskDependencies.requireReady(store,target,now);
        List<BoundInput> result=new ArrayList<>();
        for(var binding:target.spec().inputs()){
            var source=store.task(binding.taskId()).orElseThrow(()->new InputUnavailable("事实来源任务不存在"));
            if(!source.sessionId().equals(target.sessionId())||!source.goalId().equals(target.goalId())
                    ||source.goalRevision()!=target.goalRevision()||source.status()!=TaskStatus.COMPLETED)
                throw new InputUnavailable("事实来源任务不属于当前目标或尚未完成");
            var fact=of(source).stream().filter(f->f.id().equals(binding.factId())).findFirst().orElseThrow(()->new InputUnavailable("所需事实缺失或不再有效"));
            if(!fact.version().equals(binding.version())||now<fact.observedAt()||now>=fact.expiresAt())throw new InputUnavailable("所需事实已变更或过期");
            result.add(new BoundInput(binding.alias(),fact));
        }
        return List.copyOf(result);
    }
    private TaskFacts(){}
}
