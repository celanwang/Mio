package org.celanwang.mio.orchestration;

import java.util.*;

/** A current projection over trusted evidence. Model prose is never an assertion input. */
public record TaskResultAssessment(String contractId,String contractVersion,State state,String scope,
                                   List<Evidence> evidence,List<String> missingFacts) {
    public enum State { EXECUTION_ONLY,VERIFIED,MISSING_EVIDENCE,EXPIRED_EVIDENCE,INVALIDATED,CONTRACT_CHANGED,NOT_FINISHED }
    public record Evidence(String factId,String version,String evidenceId,long observedAt,long expiresAt) {}
    public TaskResultAssessment {evidence=List.copyOf(evidence);missingFacts=List.copyOf(missingFacts);}
    static TaskResultAssessment evaluate(CapabilityOutputContract contract,TaskRecord task,long now) {
        if(task.invalidation()!=null)return empty(contract,State.INVALIDATED);
        if(contract.requiredFacts().isEmpty())return empty(contract,State.EXECUTION_ONLY);
        var facts=TaskFacts.of(task);var evidence=new ArrayList<Evidence>();var missing=new ArrayList<String>();boolean expired=false;
        for(var entry:new TreeMap<>(contract.requiredFacts()).entrySet()) {
            var matching=facts.stream().filter(f->f.key().equals(entry.getKey())&&f.type().equals(entry.getValue())
                    &&f.sourceTask().equals(task.id())&&f.goalRevision()==task.goalRevision()
                    &&f.evidenceId()!=null&&!f.evidenceId().isBlank()&&f.version()!=null&&!f.version().isBlank()).toList();
            var current=matching.stream().filter(f->f.observedAt()<=now&&now<f.expiresAt()).findFirst();
            if(current.isPresent()) {
                var f=current.get();evidence.add(new Evidence(f.id(),f.version(),f.evidenceId(),f.observedAt(),f.expiresAt()));
            } else {missing.add(entry.getKey());expired|=matching.stream().anyMatch(f->now>=f.expiresAt());}
        }
        return new TaskResultAssessment(contract.id(),contract.version(),missing.isEmpty()?State.VERIFIED:expired?State.EXPIRED_EVIDENCE:State.MISSING_EVIDENCE,
                contract.description(),evidence,missing);
    }
    static TaskResultAssessment empty(CapabilityOutputContract contract,State state) {
        return new TaskResultAssessment(contract.id(),contract.version(),state,contract.description(),List.of(),List.of());
    }
    public static TaskResultAssessment current(TaskRecord task,AgentRegistry registry,long now) {
        try {
            var capability=registry.require(task.spec().capabilityId());var contract=capability.outputContract(task.spec());
            if(!capability.executionVersion().equals(task.capabilityVersion()))return empty(contract,State.CONTRACT_CHANGED);
            if(task.status()!=TaskStatus.COMPLETED) {
                if(task.status().terminal()&&task.effectStarted()&&!contract.requiredFacts().isEmpty()) {
                    var late=evaluate(contract,task,now);
                    if(late.state()==State.VERIFIED)return late;
                }
                return empty(contract,task.invalidation()!=null?State.INVALIDATED:State.NOT_FINISHED);
            }
            return evaluate(contract,task,now);
        } catch(IllegalArgumentException unavailable) {
            return new TaskResultAssessment(task.spec().outputContract(),"",State.CONTRACT_CHANGED,"原输出契约不可用，历史执行不重新解释为业务成功",List.of(),List.of());
        }
    }
}
