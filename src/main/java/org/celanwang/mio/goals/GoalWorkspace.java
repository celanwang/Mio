package org.celanwang.mio.goals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.celanwang.mio.orchestration.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;

/** User-facing outcome layer: progress, explicit actions and versioned preparation deliverables. */
public final class GoalWorkspace {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final JdbcGoalPrograms programs;private final GoalProgramExecutor executor;private final TaskRuntime runtime;private final JdbcBusinessResources resources;private final ExecutionLeaseStore leases;private final ObjectMapper json;
    public GoalWorkspace(JdbcTemplate jdbc,DataSource source,JdbcGoalPrograms programs,GoalProgramExecutor executor,TaskRuntime runtime,JdbcBusinessResources resources,ExecutionLeaseStore leases,ObjectMapper json){this.jdbc=jdbc;this.programs=programs;this.executor=executor;this.runtime=runtime;this.resources=resources;this.leases=leases;this.json=json;tx=new TransactionTemplate(new DataSourceTransactionManager(source));}
    public void initialize(){
        jdbc.execute("CREATE TABLE IF NOT EXISTS goal_notifications(id TEXT PRIMARY KEY,goal_id TEXT NOT NULL REFERENCES goals(id),fingerprint TEXT NOT NULL,title TEXT NOT NULL,created_at BIGINT NOT NULL,read_at BIGINT,UNIQUE(goal_id,fingerprint))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS goal_deliveries(id TEXT PRIMARY KEY,goal_id TEXT NOT NULL REFERENCES goals(id),version BIGINT NOT NULL,source_hash TEXT NOT NULL,content TEXT NOT NULL,ready BOOLEAN NOT NULL,created_at BIGINT NOT NULL,acknowledged_at BIGINT,UNIQUE(goal_id,version),UNIQUE(goal_id,source_hash))");
    }
    public List<Map<String,Object>> list(){return jdbc.queryForList("SELECT g.id,g.session_id,g.content::jsonb->>'latestUserInput' AS title,coalesce(g.content::jsonb->>'state','ACTIVE') AS state,p.revision AS program_revision,p.last_error FROM goals g LEFT JOIN goal_programs p ON p.goal_id=g.id ORDER BY (g.content::jsonb->>'createdAt')::bigint DESC,g.id LIMIT 100");}
    public String create(String intent){String session=UUID.randomUUID().toString();var token=leases.acquire(session,10000);try{leases.fenced(token,()->runtime.captureUser(session,intent));}finally{leases.release(token);}return session;}
    public Map<String,Object> snapshot(String session){
        var goal=runtime.goal(session);var p=programs.find(goal.id()).orElse(null);var progress=executor.status(session);var steps=(List<Map<String,Object>>)progress.get("steps");var tasks=runtime.list(session);var actions=new ArrayList<Map<String,Object>>();int finished=0;String state="WAITING";
        for(var step:steps){String s=(String)step.get("state"),id=(String)step.get("taskId");
            if(Set.of("REPORT_READY","VERIFIED").contains(s))finished++;
            if(s.equals("NEEDS_INPUT")){var task=runtime.require(session,id);actions.add(Map.of("type","ANSWER","stepId",step.get("id"),"taskId",id,"questionId",task.questionId(),"text",task.reply()));}
            else if(s.equals("INPUT_READY"))actions.add(Map.of("type","CONTINUE","stepId",step.get("id"),"taskId",id,"text","已收到回答，可继续此任务"));
            else if(Set.of("FAILED","STALE","CANCELLED","UNKNOWN","EVIDENCE_OR_CONDITION_MISSING").contains(s))actions.add(Map.of("type","REVIEW","stepId",step.get("id"),"taskId",id,"text","需要复核或重新准备；不会重放旧业务操作"));
        }
        if(p==null)state="NO_PLAN";else if(goal.revision()!=p.goalRevision())state="PLAN_STALE";
        else if(!actions.isEmpty())state=actions.stream().anyMatch(a->a.get("type").equals("ANSWER"))?"NEEDS_INPUT":"BLOCKED";
        else if(steps.stream().anyMatch(s->s.get("state").equals("RUNNING")))state="RUNNING";
        else if(finished==steps.size()&&!steps.isEmpty())state="READY";
        else if(p.policy().until()<=System.currentTimeMillis())state="EXPIRED";
        var counters=(Map<String,Object>)progress.getOrDefault("runtime",Map.of());
        if(p!=null&&state.equals("WAITING")){
            if(((Number)counters.getOrDefault("activations",0)).intValue()>=p.policy().maxActivations())state="LIMIT_REACHED";
            else if(!String.valueOf(counters.getOrDefault("last_error","")).isEmpty())state="BLOCKED";
        }
        if(goal.state()!=GoalRecord.State.ACTIVE)state=goal.state().name();
        boolean unresolved=tasks.stream().anyMatch(TaskRecord::unresolvedEffects);boolean ready=state.equals("READY")&&!unresolved;
        if(unresolved&&state.equals("READY"))state="UNRESOLVED_OPERATION";
        var currentTasks=steps.stream().map(s->tasks.stream().filter(t->t.id().equals(s.get("taskId"))).findFirst().map(t->Map.of("id",t.id(),"revision",t.revision())).orElse(Map.of())).toList();
        var business=resources.list(session);String hash=JsonFingerprint.hash(Map.of("goal",goal.id(),"goalRevision",goal.revision(),"programRevision",p==null?0:p.revision(),"tasks",currentTasks,"resources",business,"state",state));
        var result=new LinkedHashMap<String,Object>();result.put("state",state);result.put("ready",ready);result.put("completed",finished);result.put("total",steps.size());result.put("actions",actions);result.put("sourceHash",hash);result.put("progress",progress);result.put("resources",business);result.put("goal",goal);
        var deliveries=jdbc.queryForList("SELECT id,version,source_hash,ready,created_at,acknowledged_at FROM goal_deliveries WHERE goal_id=? ORDER BY version DESC LIMIT 20",goal.id());for(var delivery:deliveries)delivery.put("current",hash.equals(delivery.get("source_hash")));result.put("deliveries",deliveries);
        result.put("notifications",jdbc.queryForList("SELECT id,title,created_at,read_at FROM goal_notifications WHERE goal_id=? ORDER BY created_at DESC LIMIT 30",goal.id()));return result;
    }
    public void recordProgress(String session){var view=snapshot(session);var goal=(GoalRecord)view.get("goal");String fingerprint=JsonFingerprint.hash(Map.of("source",view.get("sourceHash"),"state",view.get("state")));jdbc.update("INSERT INTO goal_notifications(id,goal_id,fingerprint,title,created_at) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",UUID.randomUUID().toString(),goal.id(),fingerprint,view.get("state")+" · "+view.get("completed")+"/"+view.get("total"),System.currentTimeMillis());}
    public void readNotification(String session,String id){jdbc.update("UPDATE goal_notifications SET read_at=coalesce(read_at,?) WHERE id=? AND goal_id=?",System.currentTimeMillis(),id,runtime.goal(session).id());}
    public Map<String,Object> deliver(String session){var token=leases.acquire(session,15000);try{return leases.fenced(token,()->tx.execute(status->{
        var goal=runtime.goal(session);jdbc.queryForObject("SELECT id FROM goals WHERE id=? FOR UPDATE",String.class,goal.id());var view=snapshot(session);String hash=(String)view.get("sourceHash");
        var existing=jdbc.queryForList("SELECT id,version FROM goal_deliveries WHERE goal_id=? AND source_hash=?",goal.id(),hash);if(!existing.isEmpty())return existing.get(0);
        long version=jdbc.queryForObject("SELECT coalesce(max(version),0)+1 FROM goal_deliveries WHERE goal_id=?",Long.class,goal.id());String id=UUID.randomUUID().toString();var content=new StringBuilder("# 目标准备交付报告\n\n").append(goal.trustedIntent()).append("\n\n状态：").append(view.get("state")).append("\n\n本报告汇总准备工作与证据，不表示订单、支付或履约已完成。\n");
        var progress=(Map<String,Object>)view.get("progress");for(var step:(List<Map<String,Object>>)progress.get("steps"))content.append("\n## ").append(step.get("id")).append(" · ").append(step.get("state")).append("\n\n").append(step.get("reply")).append("\n\n证据评估：").append(encode(step.getOrDefault("assessment",Map.of()))).append("\n");
        content.append("\n## 待处理事项\n").append(encode(view.get("actions"))).append("\n\n## 业务资源与来源\n").append(encode(view.get("resources")));
        jdbc.update("INSERT INTO goal_deliveries(id,goal_id,version,source_hash,content,ready,created_at) VALUES (?,?,?,?,?,?,?)",id,goal.id(),version,hash,content.toString(),view.get("ready"),System.currentTimeMillis());programs.event(goal,"goal_delivery_created",Map.of("delivery",id,"version",version));return Map.of("id",id,"version",version);
    }));}finally{leases.release(token);}}
    public Map<String,Object> delivery(String session,String id){return jdbc.queryForList("SELECT * FROM goal_deliveries WHERE goal_id=? AND id=?",runtime.goal(session).id(),id).stream().findFirst().orElseThrow(()->new IllegalArgumentException("交付物不存在"));}
    public void acknowledge(String session,String id,long expectedVersion){var token=leases.acquire(session,15000);try{leases.fenced(token,()->tx.execute(status->{
        var goal=runtime.goal(session);jdbc.queryForObject("SELECT id FROM goals WHERE id=? FOR UPDATE",String.class,goal.id());var current=snapshot(session);var record=delivery(session,id);
        if(((Number)record.get("version")).longValue()!=expectedVersion||!Boolean.TRUE.equals(current.get("ready"))||!current.get("sourceHash").equals(record.get("source_hash")))throw new IllegalStateException("交付物已过期或仍有未处理事项，请重新汇总");
        jdbc.update("UPDATE goal_deliveries SET acknowledged_at=coalesce(acknowledged_at,?) WHERE id=?",System.currentTimeMillis(),id);programs.event(goal,"goal_delivery_acknowledged",Map.of("delivery",id,"scope","PREPARATION_REPORT"));return null;
    }));}finally{leases.release(token);}}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("交付物编码失败",e);}}
}
