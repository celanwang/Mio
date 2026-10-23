package org.celanwang.mio.goals;

import org.celanwang.mio.orchestration.*;
import java.math.*;
import java.util.*;

/** Explicit hard constraints first, then normalized numeric preferences. Never grants execution authority. */
public final class GoalComparison {
    public enum Direction { MIN,MAX }
    public record Criterion(String fieldId,String unit,Direction direction,BigDecimal weight,GoalAcceptance.Operator operator,String expected) {
        public Criterion {
            if(fieldId==null||unit==null||direction==null||weight==null||weight.signum()<=0||weight.compareTo(new BigDecimal("100"))>0||weight.scale()>6||weight.precision()>12)throw new IllegalArgumentException("比较指标、单位、方向或权重无效");
            if((operator==null)!=(expected==null))throw new IllegalArgumentException("硬条件需要同时提供运算符和值");
            if(expected!=null)number(expected);
        }
    }
    public record Request(long expectedRevision,long expectedGoalRevision,List<String> stepIds,List<Criterion> criteria) {
        public Request {
            stepIds=stepIds==null?List.of():List.copyOf(stepIds);criteria=criteria==null?List.of():List.copyOf(criteria);
            if(expectedRevision<1||expectedGoalRevision<1||stepIds.size()>12||new HashSet<>(stepIds).size()!=stepIds.size()||criteria.isEmpty()||criteria.size()>8||criteria.stream().map(Criterion::fieldId).distinct().count()!=criteria.size())throw new IllegalArgumentException("比较应选择至多12个步骤、1至8个不同指标及当前计划版本");
        }
    }
    public record Value(String fieldId,BigDecimal value,String unit,String evidenceId,String factVersion,long expiresAt) {}
    public record Candidate(String stepId,String taskId,boolean eligible,List<String> reasons,List<Value> values,BigDecimal score) {}
    public record Result(List<Candidate> candidates,List<Criterion> criteria,String recommendation,String scope) {}
    private GoalComparison() {}

    public static Result compare(GoalProgram program,List<TaskRecord> tasks,AgentRegistry registry,Request request) {
        var ids=program.steps().stream().map(GoalProgram.Step::id).collect(java.util.stream.Collectors.toSet());
        if(!ids.containsAll(request.stepIds()))throw new IllegalArgumentException("比较步骤不存在");
        var rows=new ArrayList<Candidate>();long now=System.currentTimeMillis();
        for(var step:program.steps()) {
            if(!request.stepIds().isEmpty()&&!request.stepIds().contains(step.id()))continue;
            var task=tasks.stream().filter(t->t.spec().key().equals(program.taskKey(step))).findFirst().orElse(null);
            var reasons=new ArrayList<String>();var values=new ArrayList<Value>();
            if(task==null)reasons.add("步骤尚未执行");
            else if(task.status()!=TaskStatus.COMPLETED||task.invalidation()!=null||TaskResultAssessment.current(task,registry,now).state()!=TaskResultAssessment.State.VERIFIED)reasons.add("缺少当前有效的可信事实");
            else {
                var contract=registry.require(step.capabilityId()).outputContract(task.spec());var facts=TaskFacts.validAt(task,now);
                for(var criterion:request.criteria()) {
                    var field=contract.fields().stream().filter(f->f.id().equals(criterion.fieldId())&&f.type()==CapabilityOutputContract.ValueType.NUMBER&&f.unit().equals(criterion.unit())).findFirst().orElse(null);
                    if(field==null){reasons.add(criterion.fieldId()+" 缺少同单位数值契约");continue;}
                    var fact=facts.stream().filter(f->f.key().equals(field.factKey())).findFirst().orElse(null);
                    try {
                        var value=number(fact==null?null:fact.value().get(field.valueKey()));
                        values.add(new Value(field.id(),value,field.unit(),fact.evidenceId(),fact.version(),fact.expiresAt()));
                        if(criterion.operator()!=null&&!new GoalAcceptance.Check(field.id(),criterion.operator(),criterion.expected()).matches(field,value.toPlainString()))reasons.add(field.id()+" 不满足比较硬条件 "+criterion.operator()+" "+criterion.expected());
                    }catch(IllegalArgumentException invalid){reasons.add(field.id()+" 缺少有效数值");}
                }
                for(var check:step.checks()) {
                    var field=contract.fields().stream().filter(f->f.id().equals(check.fieldId())).findFirst().orElse(null);
                    var actual=field==null?null:facts.stream().filter(f->f.key().equals(field.factKey())).findFirst().map(f->f.value().get(field.valueKey())).orElse(null);
                    try{if(field==null||!check.matches(field,actual))reasons.add(check.fieldId()+" 不满足已确认步骤条件");}catch(IllegalArgumentException invalid){reasons.add(check.fieldId()+" 无法核对已确认条件");}
                }
            }
            rows.add(new Candidate(step.id(),task==null?"":task.id(),reasons.isEmpty(),List.copyOf(reasons),List.copyOf(values),null));
        }
        var feasible=rows.stream().filter(Candidate::eligible).toList();var scored=new ArrayList<Candidate>();
        BigDecimal totalWeight=request.criteria().stream().map(Criterion::weight).reduce(BigDecimal.ZERO,BigDecimal::add);
        for(var candidate:rows) {
            BigDecimal score=null;
            if(candidate.eligible()) {
                score=BigDecimal.ZERO;
                for(var criterion:request.criteria()) {
                    var numbers=feasible.stream().map(c->value(c,criterion.fieldId())).toList();
                    var min=numbers.stream().min(BigDecimal::compareTo).orElseThrow();var max=numbers.stream().max(BigDecimal::compareTo).orElseThrow();var range=max.subtract(min);
                    BigDecimal normalized=range.signum()==0?BigDecimal.ONE:(criterion.direction()==Direction.MIN?max.subtract(value(candidate,criterion.fieldId())):value(candidate,criterion.fieldId()).subtract(min)).divide(range,12,RoundingMode.HALF_UP);
                    score=score.add(normalized.multiply(criterion.weight()));
                }
                score=score.divide(totalWeight,6,RoundingMode.HALF_UP);
            }
            scored.add(new Candidate(candidate.stepId(),candidate.taskId(),candidate.eligible(),candidate.reasons(),candidate.values(),score));
        }
        scored.sort(Comparator.comparing((Candidate c)->!c.eligible()).thenComparing(Candidate::score,Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(Candidate::stepId));
        String recommendation=scored.stream().filter(Candidate::eligible).findFirst().map(c->c.stepId()+" 在本次条件和权重下得分最高；同分按步骤编号展示，仍需用户选择").orElse("没有证据完整且满足硬条件的候选，请补充资料或调整方案");
        return new Result(List.copyOf(scored),request.criteria(),recommendation,"仅比较当前候选的可信数值；分数不是成功概率，不批准执行");
    }
    private static BigDecimal value(Candidate candidate,String field){return candidate.values().stream().filter(v->v.fieldId().equals(field)).findFirst().orElseThrow().value();}
    private static BigDecimal number(String value){try{if(value==null||value.length()>100)throw new NumberFormatException();var number=new BigDecimal(value);if(Math.abs((long)number.scale())>100||number.precision()>100)throw new NumberFormatException();return number;}catch(NumberFormatException e){throw new IllegalArgumentException("数值无效");}}
}
