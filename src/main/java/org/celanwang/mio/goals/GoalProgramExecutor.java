package org.celanwang.mio.goals;

import org.celanwang.mio.orchestration.*;
import java.time.*;
import java.util.*;

/** Runs confirmed preparation steps; no supervisor call or business approval is synthesized. */
public final class GoalProgramExecutor {
    private final JdbcGoalPrograms programs;private final JdbcGoalWakeups wakeups;private final TaskRuntime runtime;private final ExecutionLeaseStore leases;private final GoalBudgetStore budgets;private final AgentRegistry registry;
    private final RunBudget.Limits limits;private final GoalBudgetStore.Limits goalLimits;
    public GoalProgramExecutor(JdbcGoalPrograms programs,JdbcGoalWakeups wakeups,TaskRuntime runtime,ExecutionLeaseStore leases,GoalBudgetStore budgets,AgentRegistry registry,RunBudget.Limits limits,GoalBudgetStore.Limits goalLimits){this.programs=programs;this.wakeups=wakeups;this.runtime=runtime;this.leases=leases;this.budgets=budgets;this.registry=registry;this.limits=limits;this.goalLimits=goalLimits;}
    public void run(JdbcGoalWakeups.Claim claim){
        ExecutionLeaseStore.Token token;try{token=leases.acquire(claim.session(),Math.min(900000,limits.millis()+5000));}catch(ExecutionLeaseStore.Busy busy){wakeups.busy(claim);return;}
        String error="";
        try{
            var candidate=wakeups.begin(claim);var stale=new HashSet<String>();var history=runtime.list(claim.session());
            for(var step:candidate.steps())history.stream().filter(t->t.goalRevision()==candidate.goalRevision()&&t.spec().key().equals(candidate.taskKey(step))).filter(t->TaskResultAssessment.current(t,registry,System.currentTimeMillis()).state()==TaskResultAssessment.State.EXPIRED_EVIDENCE).findFirst().ifPresent(t->stale.add(step.id()));
            final var p=stale.isEmpty()?candidate:programs.refresh(claim.session(),candidate.revision(),stale);var goal=runtime.goal(claim.session());var budget=new RunBudget(limits,Clock.systemUTC());budget.bindLease(leases,token);budget.bind(budgets,goal.id(),goalLimits,true);
            budget.bindGoalGuard(()->{var current=runtime.goal(claim.session());current.requireActive();if(current.revision()!=p.goalRevision()||current.controlRevision()!=goal.controlRevision()||programs.find(goal.id()).orElseThrow().revision()!=p.revision()||System.currentTimeMillis()>=p.policy().until())throw new GoalRecord.Stopped();});
            var completed=new HashMap<String,TaskRecord>();var remaining=new ArrayList<>(p.steps());
            while(!remaining.isEmpty()){
                var domains=new HashSet<String>();var ready=remaining.stream().filter(s->completed.keySet().containsAll(s.dependencies())).filter(s->domains.add(s.capabilityId())).limit(p.policy().parallelism()).toList();if(ready.isEmpty())break;
                var parents=Map.copyOf(completed);
                var results=reactor.core.publisher.Flux.fromIterable(ready).flatMap(step->reactor.core.publisher.Mono.fromCallable(()->Map.entry(step,executeStep(claim.session(),p,step,parents,budget))).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()),p.policy().parallelism()).collectList().block(Duration.ofMillis(limits.millis()));
                for(var result:results){remaining.remove(result.getKey());if(ready(result.getKey(),result.getValue()))completed.put(result.getKey().id(),result.getValue());}
            }
            if(p.policy().trackResources())for(var task:runtime.list(claim.session()))for(var operation:task.operations())if(runtime.canReconcile(operation)){
                budget.active();runtime.reconcile(claim.session(),task.id(),operation.id(),budget).block(Duration.ofSeconds(30));
            }
            if(completed.size()==p.steps().size()&&!p.policy().trackResources()){
                long next=completed.values().stream().flatMap(t->TaskFacts.of(t).stream()).mapToLong(TaskFact::expiresAt).filter(at->at>System.currentTimeMillis()).min().orElse(p.policy().until());wakeups.waitUntil(p,Math.min(next,p.policy().until()));
            }
        }catch(Exception failure){error=failure instanceof RunBudget.BudgetExceeded?"BUDGET_EXHAUSTED":failure instanceof GoalRecord.Stopped?"GOAL_CHANGED":"PREPARATION_STOPPED";}
        finally{try{wakeups.finish(claim,error);}finally{leases.release(token);}}
    }
    private TaskRecord executeStep(String session,GoalProgram p,GoalProgram.Step step,Map<String,TaskRecord> completed,RunBudget budget){
        budget.active();var task=runtime.list(session).stream().filter(t->t.goalRevision()==p.goalRevision()&&t.spec().key().equals(p.taskKey(step))).findFirst().orElse(null);
        if(task==null)task=runtime.submit(session,p.task(step,completed),budget).block(Duration.ofMillis(limits.millis()));
        if(task.status()==TaskStatus.QUEUED)task=runtime.runQueuedTask(session,task.id(),budget).block(Duration.ofMillis(limits.millis()));
        else if(task.status()==TaskStatus.INPUT_READY)task=runtime.continueTask(session,task.id(),budget).block(Duration.ofMillis(limits.millis()));return task;
    }
    private boolean ready(GoalProgram.Step step,TaskRecord task){
        if(task.status()!=TaskStatus.COMPLETED||task.invalidation()!=null)return false;var assessment=TaskResultAssessment.current(task,registry,System.currentTimeMillis());if(assessment.state()!=TaskResultAssessment.State.VERIFIED&&assessment.state()!=TaskResultAssessment.State.EXECUTION_ONLY)return false;
        var contract=registry.require(task.spec().capabilityId()).outputContract(task.spec());
        for(var check:step.checks()){var field=contract.fields().stream().filter(f->f.id().equals(check.fieldId())).findFirst().orElseThrow();var actual=TaskFacts.validAt(task,System.currentTimeMillis()).stream().filter(f->f.key().equals(field.factKey())).findFirst().map(f->f.value().get(field.valueKey())).orElse(null);if(!check.matches(field,actual))return false;}return true;
    }
    public Map<String,Object> status(String session){
        var view=programs.view(session);var p=(GoalProgram)view.get("program");if(p==null)return Map.of("steps",List.of());var tasks=runtime.list(session);var steps=new ArrayList<Map<String,Object>>();
        for(var step:p.steps()){
            var task=tasks.stream().filter(t->t.goalRevision()==p.goalRevision()&&t.spec().key().equals(p.taskKey(step))).findFirst().orElse(null);
            var state=task==null?"WAITING":task.status().name();if(task!=null&&task.status()==TaskStatus.COMPLETED)state=ready(step,task)?task.spec().outputContract().equals("report")?"REPORT_READY":"VERIFIED":"EVIDENCE_OR_CONDITION_MISSING";var result=new LinkedHashMap<String,Object>();result.put("id",step.id());result.put("state",state);result.put("reply",task==null?"":task.reply());result.put("taskId",task==null?"":task.id());
            if(task!=null)result.put("assessment",TaskResultAssessment.current(task,registry,System.currentTimeMillis()));steps.add(result);
        }
        var rows=(List<?>)view.get("runtime");return Map.of("steps",steps,"runtime",rows.isEmpty()?Map.of():rows.get(0),"wakeups",wakeups.recent(p.goalId()),"programRevision",p.revision());
    }
    public List<Map<String,Object>> compare(String session,String fieldId,boolean descending){
        var goal=runtime.goal(session);var p=programs.find(goal.id()).orElseThrow();var tasks=runtime.list(session);var result=new ArrayList<Map<String,Object>>();
        for(var step:p.steps()){
            var task=tasks.stream().filter(t->t.goalRevision()==p.goalRevision()&&t.spec().key().equals(p.taskKey(step))).findFirst().orElse(null);if(task==null||!ready(step,task))continue;
            var contract=registry.require(step.capabilityId()).outputContract(task.spec());var field=contract.fields().stream().filter(f->f.id().equals(fieldId)&&f.type()==CapabilityOutputContract.ValueType.NUMBER).findFirst().orElse(null);if(field==null)continue;
            var fact=TaskFacts.validAt(task,System.currentTimeMillis()).stream().filter(f->f.key().equals(field.factKey())).findFirst().orElse(null);if(fact==null)continue;
            var value=fact.value().get(field.valueKey());var number=new java.math.BigDecimal(value);result.add(Map.of("stepId",step.id(),"field",fieldId,"value",number,"unit",field.unit(),"evidenceId",fact.evidenceId(),"expiresAt",fact.expiresAt()));
        }
        Comparator<Map<String,Object>> byValue=Comparator.comparing(r->(java.math.BigDecimal)r.get("value"));if(descending)byValue=byValue.reversed();result.sort(Comparator.comparing((Map<String,Object> r)->(String)r.get("unit")).thenComparing(byValue));return result;
    }
}
