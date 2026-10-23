package org.celanwang.mio.goals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.celanwang.mio.orchestration.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

/** Immutable comparison snapshots with current-evidence checks for later delivery. */
public final class GoalComparisons {
    private final JdbcTemplate jdbc;private final JdbcGoalPrograms programs;private final TaskRuntime runtime;private final AgentRegistry registry;private final ObjectMapper json;
    public GoalComparisons(JdbcTemplate jdbc,JdbcGoalPrograms programs,TaskRuntime runtime,AgentRegistry registry,ObjectMapper json){this.jdbc=jdbc;this.programs=programs;this.runtime=runtime;this.registry=registry;this.json=json;}
    public Map<String,Object> compare(String session,GoalComparison.Request request){
        var goal=runtime.goal(session);var program=programs.find(goal.id()).orElseThrow();
        if(goal.revision()!=request.expectedGoalRevision()||program.goalRevision()!=goal.revision()||program.revision()!=request.expectedRevision())throw new IllegalStateException("计划或用户目标已改变，请刷新后比较");
        String hash=programs.evidenceHash(session,program);var tasks=runtime.planTasks(session,program.goalRevision(),program.steps().stream().map(program::taskKey).collect(java.util.stream.Collectors.toSet()));
        var result=GoalComparison.compare(program,tasks,registry,request);
        if(!programs.evidenceHash(session,program).equals(hash)||programs.find(goal.id()).orElseThrow().revision()!=program.revision()||runtime.goal(session).revision()!=goal.revision())throw new IllegalStateException("比较期间证据已改变，请重试");
        String id=UUID.randomUUID().toString();jdbc.update("INSERT INTO goal_comparisons(id,goal_id,program_revision,goal_revision,evidence_hash,request,result,created_at) VALUES (?,?,?,?,?,?,?,?)",id,goal.id(),program.revision(),goal.revision(),hash,encode(request),encode(result),System.currentTimeMillis());
        return Map.of("id",id,"result",result,"evidenceHash",hash,"current",true);
    }
    public List<Map<String,Object>> recent(String session){
        var goal=runtime.goal(session);var program=programs.find(goal.id()).orElse(null);String hash=programs.evidenceHash(session,program);
        var rows=jdbc.queryForList("SELECT * FROM goal_comparisons WHERE goal_id=? ORDER BY created_at DESC,id DESC LIMIT 10",goal.id());
        for(var row:rows){row.put("current",program!=null&&((Number)row.get("program_revision")).longValue()==program.revision()&&((Number)row.get("goal_revision")).longValue()==goal.revision()&&row.get("evidence_hash").equals(hash));try{row.put("result",json.readValue((String)row.get("result"),GoalComparison.Result.class));row.put("request",json.readValue((String)row.get("request"),GoalComparison.Request.class));}catch(Exception corrupt){throw new IllegalStateException("比较快照不可读",corrupt);}}
        return rows;
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("比较内容无效",e);}}
}
