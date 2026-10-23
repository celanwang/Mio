package org.celanwang.mio.goals;

import org.celanwang.mio.orchestration.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;

/** Durable, coalesced wakeups. An activation lost after starting is recorded failed, never replayed. */
public final class JdbcGoalWakeups {
    public record Claim(String id,String goalId,String session,long revision,String owner){}
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final JdbcGoalPrograms programs;private final CoordinationStore store;
    public JdbcGoalWakeups(JdbcTemplate jdbc,DataSource source,JdbcGoalPrograms programs,CoordinationStore store){this.jdbc=jdbc;this.programs=programs;this.store=store;tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(10);}
    public void initialize(){jdbc.execute("CREATE TABLE IF NOT EXISTS goal_wakeups(id TEXT PRIMARY KEY,goal_id TEXT NOT NULL REFERENCES goal_programs(goal_id),program_revision BIGINT NOT NULL,state TEXT NOT NULL,owner TEXT,lease_until BIGINT NOT NULL DEFAULT 0,next_at BIGINT NOT NULL DEFAULT 0,created_at BIGINT NOT NULL,error_code TEXT NOT NULL DEFAULT '')");jdbc.execute("CREATE INDEX IF NOT EXISTS idx_goal_wakeups_ready ON goal_wakeups(state,next_at,created_at)");}
    private long now(){return jdbc.queryForObject("SELECT floor(extract(epoch FROM clock_timestamp())*1000)::bigint",Long.class);}
    public String wake(String session,String requestId){
        if(requestId==null||!requestId.matches("[A-Za-z0-9_-]{1,100}"))throw new IllegalArgumentException("推进请求编号无效");
        return tx.execute(status->{var goal=store.goal(session).orElseThrow();goal.requireActive();var p=programs.find(goal.id()).orElseThrow(()->new IllegalArgumentException("请先确认计划"));if(p.goalRevision()!=goal.revision()||p.policy().until()<=now())throw new IllegalStateException("计划已过期或目标已改变");String id="manual:"+goal.id()+":"+requestId;enqueue(id,p);return id;});
    }
    private void enqueue(String id,GoalProgram p){jdbc.update("INSERT INTO goal_wakeups(id,goal_id,program_revision,state,created_at) VALUES (?,?,?,'READY',?) ON CONFLICT DO NOTHING",id,p.goalId(),p.revision(),now());}
    public void signal(TaskRecord task){
        var p=programs.find(task.goalId()).orElse(null);if(p==null||!p.policy().enabled()||p.goalRevision()!=task.goalRevision())return;
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM goal_wakeups WHERE goal_id=? AND program_revision=? AND state IN ('READY','RUNNING'))",Boolean.class,p.goalId(),p.revision())))return;
        var step=p.steps().stream().filter(s->p.taskKey(s).equals(task.spec().key())).findFirst().orElse(null);if(step==null)return;
        if(task.status()==TaskStatus.INPUT_READY||task.status()==TaskStatus.COMPLETED&&p.steps().stream().anyMatch(s->s.dependencies().contains(step.id())))enqueue("task:"+task.id()+":"+task.revision(),p);
    }
    public void scheduleDue(){tx.executeWithoutResult(status->{
        long at=now();
        jdbc.update("UPDATE goal_wakeups SET state='FAILED',owner=NULL,error_code='ACTIVATION_INTERRUPTED' WHERE state='RUNNING' AND lease_until<=?",at);
        for(String id:jdbc.query("SELECT p.goal_id FROM goal_programs p JOIN goals g ON g.id=p.goal_id WHERE (p.content::jsonb->'policy'->>'enabled')::boolean=true AND p.next_at<=? AND (p.content::jsonb->'policy'->>'until')::bigint>? AND p.activations<(p.content::jsonb->'policy'->>'maxActivations')::int AND coalesce(g.content::jsonb->>'state','ACTIVE')='ACTIVE' AND g.revision=(p.content::jsonb->>'goalRevision')::bigint ORDER BY p.next_at LIMIT 20 FOR UPDATE OF p SKIP LOCKED",(r,n)->r.getString(1),at,at)){
            var p=programs.find(id).orElseThrow();var goal=jdbc.queryForObject("SELECT session_id FROM goals WHERE id=?",String.class,id);if(store.goal(goal).orElseThrow().revision()!=p.goalRevision())continue;
            if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM goal_wakeups WHERE goal_id=? AND state IN ('READY','RUNNING') AND program_revision=?)",Boolean.class,id,p.revision())))enqueue("timer:"+id+":"+p.revision()+":"+at,p);
            jdbc.update("UPDATE goal_programs SET next_at=? WHERE goal_id=?",at+p.policy().intervalSeconds()*1000L,id);
        }
    });}
    public Optional<Claim> claim(){return tx.execute(status->{
        var rows=jdbc.queryForList("SELECT w.id,w.goal_id,w.program_revision,g.session_id FROM goal_wakeups w JOIN goals g ON g.id=w.goal_id WHERE w.state='READY' AND w.next_at<=? ORDER BY w.created_at LIMIT 1 FOR UPDATE OF w SKIP LOCKED",now());if(rows.isEmpty())return Optional.empty();var r=rows.get(0);String owner=UUID.randomUUID().toString();jdbc.update("UPDATE goal_wakeups SET state='RUNNING',owner=?,lease_until=? WHERE id=?",owner,now()+180000,r.get("id"));return Optional.of(new Claim((String)r.get("id"),(String)r.get("goal_id"),(String)r.get("session_id"),((Number)r.get("program_revision")).longValue(),owner));
    });}
    public GoalProgram begin(Claim claim){return tx.execute(status->{
        var row=jdbc.queryForMap("SELECT revision,activations FROM goal_programs WHERE goal_id=? FOR UPDATE",claim.goalId());var p=programs.find(claim.goalId()).orElseThrow();var goal=store.goal(claim.session()).orElseThrow();goal.requireActive();
        if(p.revision()!=claim.revision()||p.goalRevision()!=goal.revision()||p.policy().until()<=now()||((Number)row.get("activations")).intValue()>=p.policy().maxActivations())throw new IllegalStateException("计划已改变、过期或唤醒额度耗尽");
        jdbc.update("UPDATE goal_programs SET activations=activations+1,last_error='' WHERE goal_id=?",p.goalId());programs.event(goal,"goal_activation_started",Map.of("wakeup",claim.id(),"programRevision",p.revision()));return p;
    });}
    public void finish(Claim claim,String error){tx.executeWithoutResult(status->{
        if(jdbc.update("UPDATE goal_wakeups SET state=?,owner=NULL,error_code=? WHERE id=? AND owner=? AND state='RUNNING'",error.isEmpty()?"DONE":"FAILED",error,claim.id(),claim.owner())!=1)throw new IllegalStateException("唤醒身份已失效");
        jdbc.update("UPDATE goal_programs SET last_error=? WHERE goal_id=?",error,claim.goalId());programs.event(store.goal(claim.session()).orElseThrow(),"goal_activation_finished",Map.of("wakeup",claim.id(),"error",error));
    });}
    public void busy(Claim claim){jdbc.update("UPDATE goal_wakeups SET state='READY',owner=NULL,next_at=? WHERE id=? AND owner=?",now()+5000,claim.id(),claim.owner());}
    public List<Map<String,Object>> recent(String goal){return jdbc.queryForList("SELECT id,state,error_code,created_at FROM goal_wakeups WHERE goal_id=? ORDER BY created_at DESC LIMIT 20",goal);}
    public void waitUntil(GoalProgram program,long at){jdbc.update("UPDATE goal_programs SET next_at=? WHERE goal_id=? AND revision=?",at,program.goalId(),program.revision());}
}
