package org.celanwang.mio.orchestration;

import java.util.*;

/** Initial fact projection. Provider-resource identity is timeless; no claim about its current state. */
public final class TaskFacts {
    public static final class InputUnavailable extends IllegalStateException {
        public InputUnavailable(String message){super(message);}
    }
    public record BoundInput(String alias,TaskFact fact) {}
    public static List<TaskFact> of(TaskRecord task){
        List<TaskFact> result=new ArrayList<>();
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
    public static List<BoundInput> bind(CoordinationStore store,TaskRecord target,long now){
        List<BoundInput> result=new ArrayList<>();
        for(var binding:target.spec().inputs()){
            var source=store.task(binding.taskId()).orElseThrow(()->new InputUnavailable("事实来源任务不存在"));
            if(!source.sessionId().equals(target.sessionId())||!source.goalId().equals(target.goalId())
                    ||source.goalRevision()!=target.goalRevision()||source.status()!=TaskStatus.COMPLETED)
                throw new InputUnavailable("事实来源任务不属于当前目标或尚未完成");
            var fact=of(source).stream().filter(f->f.id().equals(binding.factId())).findFirst().orElseThrow(()->new InputUnavailable("所需事实缺失或不再有效"));
            if(!fact.version().equals(binding.version())||now>=fact.expiresAt())throw new InputUnavailable("所需事实已变更或过期");
            result.add(new BoundInput(binding.alias(),fact));
        }
        return List.copyOf(result);
    }
    private TaskFacts(){}
}
