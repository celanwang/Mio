package org.celanwang.mio.goals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.celanwang.mio.orchestration.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.function.Function;

/** Persisted selection constraints apply regardless of the UI or approval route. */
public final class SelectedExecutionConditions {
    private final JdbcTemplate jdbc;private final CoordinationStore store;private final AgentRegistry registry;private final ObjectMapper json;
    public SelectedExecutionConditions(JdbcTemplate jdbc,CoordinationStore store,AgentRegistry registry,ObjectMapper json){this.jdbc=jdbc;this.store=store;this.registry=registry;this.json=json;}
    public long current(TaskRecord task){
        var rows=jdbc.queryForList("SELECT s.*,p.revision AS current_program_revision FROM goal_selections s LEFT JOIN goal_programs p ON p.goal_id=s.goal_id WHERE s.goal_id=? AND s.execution_key=? AND s.state='EXECUTION_REQUESTED'",task.goalId(),task.spec().key());
        if(rows.isEmpty()){
            boolean selected=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM goal_selections WHERE goal_id=? AND execution_goal_revision=? AND state='EXECUTION_REQUESTED')",Boolean.class,task.goalId(),task.goalRevision()));
            if(selected&&task.spec().scope()==TaskScope.REQUEST_APPROVAL)throw new IllegalStateException("当前用户版本只允许所选执行任务；追加业务需要新的明确用户要求");
            return Long.MAX_VALUE;
        }
        if(rows.size()!=1)throw new IllegalStateException("执行任务对应多个选择，已停止提交");
        return verify(rows.get(0),task,id->store.task(id).orElseThrow(),registry,json);
    }
    public static long verify(Map<String,Object> row,TaskRecord current,Function<String,TaskRecord> loadSource,AgentRegistry registry,ObjectMapper json){
        if(row.containsKey("current_program_revision")&&!Objects.equals(row.get("program_revision"),row.get("current_program_revision")))throw new IllegalStateException("所选准备计划已改变，请重新选择");
        if(row.get("execution_goal_revision")!=null&&((Number)row.get("execution_goal_revision")).longValue()!=current.goalRevision())throw new IllegalStateException("所选执行目标版本已改变，请重新选择");
        if(!current.goalId().equals(row.get("goal_id"))||!current.spec().key().equals("execute_"+row.get("id").toString().replace("-","")))throw new IllegalArgumentException("执行条件不属于本次选择");
        try{
            var stored=json.readTree((String)row.get("content"));var step=json.treeToValue(stored.get("step"),GoalProgram.Step.class);var source=loadSource.apply((String)row.get("source_task"));var capability=registry.require(step.capabilityId());
            if(!source.goalId().equals(current.goalId())||!source.sessionId().equals(current.sessionId())||!source.spec().capabilityId().equals(step.capabilityId()))throw new IllegalStateException("所选来源与执行任务不一致");
            if(source.revision()!=((Number)row.get("source_revision")).longValue()||source.invalidation()!=null)throw new IllegalStateException("所选准备来源已改变，请重新选择");
            if(!capability.executionVersion().equals(source.capabilityVersion()))throw new IllegalStateException("来源契约已改变，请重新准备");
            var contract=capability.outputContract(source.spec());var facts=TaskFacts.validAt(current,System.currentTimeMillis());long until=Long.MAX_VALUE;
            for(var required:contract.requiredFacts().entrySet()){var fact=facts.stream().filter(f->f.key().equals(required.getKey())&&f.type().equals(required.getValue())).findFirst().orElseThrow(()->new IllegalStateException("执行前需要重新取得当前任务的可信准备事实"));until=Math.min(until,fact.expiresAt());}
            var checks=new ArrayList<>(step.checks());var criteria=stored.path("comparison").path("criteria");
            if(criteria.isArray())for(var node:criteria){var criterion=json.treeToValue(node,GoalComparison.Criterion.class);if(criterion.operator()!=null){var field=contract.fields().stream().filter(f->f.id().equals(criterion.fieldId())&&f.unit().equals(criterion.unit())).findFirst().orElseThrow(()->new IllegalStateException("比较字段或单位已改变"));checks.add(new GoalAcceptance.Check(field.id(),criterion.operator(),criterion.expected()));}}
            for(var check:checks){var field=contract.fields().stream().filter(f->f.id().equals(check.fieldId())).findFirst().orElseThrow();var fact=facts.stream().filter(f->f.key().equals(field.factKey())).findFirst().orElseThrow(()->new IllegalStateException("当前执行缺少条件所需的事实"));if(!check.matches(field,fact.value().get(field.valueKey())))throw new IllegalStateException("当前方案不满足已选硬条件："+field.id());until=Math.min(until,fact.expiresAt());}
            return until;
        }catch(com.fasterxml.jackson.core.JsonProcessingException malformed){throw new IllegalStateException("选择条件不可读",malformed);}
    }
}
