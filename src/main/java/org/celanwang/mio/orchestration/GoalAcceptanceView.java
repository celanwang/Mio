package org.celanwang.mio.orchestration;

import java.util.*;

/** Current, read-only evaluation of precisely the scope confirmed by the user. */
public record GoalAcceptanceView(String goalId,long goalRevision,long revision,long checkedAt,Long validUntil,State state,Map<String,Long> taskRevisions,
                                 GoalAcceptance confirmed,List<Candidate> candidates,List<RequirementResult> results,List<Blocker> blockers) {
    public GoalAcceptanceView {taskRevisions=Map.copyOf(taskRevisions);candidates=List.copyOf(candidates);results=List.copyOf(results);blockers=List.copyOf(blockers);}
    public enum State { NOT_DEFINED,STALE,PENDING,PARTIALLY_SATISFIED,NOT_SATISFIED,BLOCKED,SATISFIED }
    public record Candidate(String taskId,String objective,String contractId,String contractVersion,String scope,List<CapabilityOutputContract.Field> fields) {}
    public record CheckResult(String fieldId,String label,GoalAcceptance.Operator operator,String expected,String actual,boolean matched) {}
    public record RequirementResult(String taskId,String objective,String state,String scope,List<TaskResultAssessment.Evidence> evidence,List<CheckResult> checks) {}
    public record Blocker(String taskId,String reason) {}

    static GoalAcceptanceView evaluate(GoalRecord goal,List<TaskRecord> tasks,GoalAcceptance confirmed,AgentRegistry registry,CoordinationStore store,long now) {
        var candidates=new ArrayList<Candidate>();var results=new ArrayList<RequirementResult>();var blockers=new ArrayList<Blocker>();
        for(var task:tasks) {
            if(task.unresolvedEffects())blockers.add(new Blocker(task.id(),"存在尚未核实的业务操作"));
            if(task.goalRevision()!=goal.revision())continue;
            try {
                var capability=registry.require(task.spec().capabilityId());var contract=capability.outputContract(task.spec());
                if(capability.executionVersion().equals(task.capabilityVersion())&&!contract.requiredFacts().isEmpty())
                    candidates.add(new Candidate(task.id(),task.spec().objective(),contract.id(),contract.version(),contract.description(),contract.fields()));
            }catch(IllegalArgumentException removed){/* Removed contracts cannot be selected or silently substituted. */}
        }
        long revision=confirmed==null?0:confirmed.revision();
        var taskRevisions=new TreeMap<String,Long>();tasks.forEach(t->taskRevisions.put(t.id(),t.revision()));
        if(confirmed==null||confirmed.requirements().isEmpty())return new GoalAcceptanceView(goal.id(),goal.revision(),revision,now,null,State.NOT_DEFINED,taskRevisions,confirmed,candidates,List.of(),blockers);
        if(!confirmed.goalId().equals(goal.id()))throw new IllegalStateException("目标验收归属不匹配");
        if(confirmed.goalRevision()!=goal.revision())return new GoalAcceptanceView(goal.id(),goal.revision(),revision,now,null,State.STALE,taskRevisions,confirmed,candidates,List.of(),blockers);
        var byId=new HashMap<String,TaskRecord>();tasks.forEach(t->byId.put(t.id(),t));
        for(var requirement:confirmed.requirements())results.add(evaluateRequirement(goal,byId.get(requirement.taskId()),requirement,registry,store,now));
        long met=results.stream().filter(r->r.state().equals("SATISFIED")).count();
        boolean active=tasks.stream().anyMatch(t->t.goalRevision()==goal.revision()&&!t.status().terminal());
        State state=!blockers.isEmpty()?State.BLOCKED:met==results.size()?(active?State.PENDING:State.SATISFIED):
                met>0?State.PARTIALLY_SATISFIED:results.stream().anyMatch(r->r.state().equals("NOT_FINISHED"))?State.PENDING:State.NOT_SATISFIED;
        // Retained proofs can expire even while another requirement or pending task is unresolved.
        Long validUntil=results.stream().flatMap(r->r.evidence().stream()).mapToLong(TaskResultAssessment.Evidence::expiresAt).filter(t->t>now&&t<Long.MAX_VALUE).min().stream().boxed().findFirst().orElse(null);
        return new GoalAcceptanceView(goal.id(),goal.revision(),revision,now,validUntil,state,taskRevisions,confirmed,candidates,results,blockers);
    }
    private static RequirementResult evaluateRequirement(GoalRecord goal,TaskRecord task,GoalAcceptance.Requirement requirement,AgentRegistry registry,CoordinationStore store,long now) {
        if(task==null)return missing(requirement,"任务记录缺失","MISSING_EVIDENCE");
        if(!task.goalId().equals(goal.id())||task.goalRevision()!=goal.revision())return missing(requirement,task.spec().objective(),"GOAL_CHANGED");
        if(!task.capabilityVersion().equals(requirement.capabilityVersion())||!task.spec().outputContract().equals(requirement.contractId()))return missing(requirement,task.spec().objective(),"CONTRACT_CHANGED");
        CapabilityOutputContract contract;
        try {contract=registry.require(task.spec().capabilityId()).outputContract(task.spec());}
        catch(IllegalArgumentException unavailable){return missing(requirement,task.spec().objective(),"CONTRACT_CHANGED");}
        if(!contract.version().equals(requirement.contractVersion())||contract.requiredFacts().isEmpty())return missing(requirement,task.spec().objective(),"CONTRACT_CHANGED");
        if(TaskDependencies.invalidation(store,task,now).isPresent())return missing(requirement,task.spec().objective(),"INVALIDATED");
        var assessment=TaskResultAssessment.current(task,registry,now);
        if(assessment.state()!=TaskResultAssessment.State.VERIFIED)return new RequirementResult(task.id(),task.spec().objective(),assessment.state().name(),assessment.scope(),assessment.evidence(),List.of());
        var checks=new ArrayList<CheckResult>();boolean malformed=false;
        for(var check:requirement.checks()) {
            var field=contract.fields().stream().filter(f->f.id().equals(check.fieldId())).findFirst().orElse(null);
            if(field==null)return missing(requirement,task.spec().objective(),"CONTRACT_CHANGED");
            var fact=TaskFacts.validAt(task,now).stream().filter(f->f.key().equals(field.factKey())&&f.type().equals(contract.requiredFacts().get(field.factKey()))).findFirst();
            String actual=fact.map(f->f.value().get(field.valueKey())).orElse(null);boolean matched=false;
            try {check.validate(field);matched=check.matches(field,actual);}catch(IllegalArgumentException invalid){malformed=true;}
            checks.add(new CheckResult(field.id(),field.label(),check.operator(),check.expected(),actual==null?null:actual.substring(0,Math.min(500,actual.length())),matched));
        }
        return new RequirementResult(task.id(),task.spec().objective(),malformed?"INVALID_VALUE":checks.stream().allMatch(CheckResult::matched)?"SATISFIED":"CONSTRAINT_NOT_MET",assessment.scope(),assessment.evidence(),checks);
    }
    private static RequirementResult missing(GoalAcceptance.Requirement requirement,String objective,String state){return new RequirementResult(requirement.taskId(),objective,state,"需要重新核对任务与证据",List.of(),List.of());}
}
