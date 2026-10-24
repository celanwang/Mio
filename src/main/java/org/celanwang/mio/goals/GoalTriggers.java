package org.celanwang.mio.goals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.celanwang.mio.orchestration.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;

/** User-owned event policies reuse the preparation scheduler. No business writes or model-made triggers. */
public final class GoalTriggers {
    public enum Kind { AT_TIME,EVIDENCE_EXPIRING,RESOURCE_CHANGED }
    public enum Action { NOTIFY,REFRESH_PREPARATION }
    public record Request(long expectedRevision,long expectedGoalRevision,Kind kind,Action action,Set<String> stepIds,long at,long until,int cooldownSeconds,int maxActivations){
        public Request{stepIds=stepIds==null?Set.of():Set.copyOf(stepIds);if(expectedRevision<1||expectedGoalRevision<1||kind==null||action==null||stepIds.size()>12||cooldownSeconds<60||cooldownSeconds>86400||maxActivations<1||maxActivations>100)throw new IllegalArgumentException("触发器类型、范围、冷却或次数无效");if(action==Action.REFRESH_PREPARATION&&stepIds.isEmpty()||kind==Kind.EVIDENCE_EXPIRING&&stepIds.isEmpty())throw new IllegalArgumentException("请明确选择需要观察或刷新的步骤");}
    }
    private final JdbcTemplate jdbc;private final JdbcGoalPrograms programs;private final JdbcGoalWakeups wakeups;private final CoordinationStore store;private final JdbcBusinessResources resources;private final ObjectMapper json;private final TransactionTemplate tx;
    public GoalTriggers(JdbcTemplate jdbc,DataSource source,JdbcGoalPrograms programs,JdbcGoalWakeups wakeups,CoordinationStore store,JdbcBusinessResources resources,ObjectMapper json){this.jdbc=jdbc;this.programs=programs;this.wakeups=wakeups;this.store=store;this.resources=resources;this.json=json;tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(10);}
    public String create(String session,Request request){return tx.execute(status->{
        var goal=store.goal(session).orElseThrow();goal.requireActive();jdbc.queryForObject("SELECT id FROM goals WHERE id=? FOR UPDATE",String.class,goal.id());var p=programs.find(goal.id()).orElseThrow();long now=System.currentTimeMillis();
        if(p.revision()!=request.expectedRevision()||goal.revision()!=request.expectedGoalRevision()||p.goalRevision()!=goal.revision())throw new IllegalStateException("计划或目标已改变，请刷新");
        if(request.until()<=now||request.until()>now+7*86400000L||request.until()>p.policy().until()||request.kind()==Kind.AT_TIME&&(request.at()<now||request.at()>=request.until()))throw new IllegalArgumentException("触发窗口应在当前计划范围内，指定时间应在窗口内");
        if(!p.steps().stream().map(GoalProgram.Step::id).collect(java.util.stream.Collectors.toSet()).containsAll(request.stepIds()))throw new IllegalArgumentException("触发器步骤不存在");
        if(request.action()==Action.REFRESH_PREPARATION&&!p.policy().enabled())throw new IllegalStateException("自动刷新需要先确认允许后台准备");
        if(jdbc.queryForObject("SELECT count(*) FROM goal_triggers WHERE goal_id=? AND state='ACTIVE'",Long.class,goal.id())>=20)throw new IllegalStateException("每个目标至多20个活动触发器");
        String id=UUID.randomUUID().toString(),baseline=request.kind()==Kind.RESOURCE_CHANGED?resourceFingerprint(session):"";
        jdbc.update("INSERT INTO goal_triggers(id,goal_id,program_revision,goal_revision,revision,content,state,last_fingerprint,next_at,created_at) VALUES (?,?,?,?,1,?,'ACTIVE',?,?,?)",id,goal.id(),p.revision(),goal.revision(),encode(request),baseline,request.kind()==Kind.AT_TIME?request.at():now,now);programs.event(goal,"goal_trigger_confirmed",Map.of("id",id,"policy",request));return id;
    });}
    public List<Map<String,Object>> list(String session){return jdbc.queryForList("SELECT t.* FROM goal_triggers t JOIN goals g ON g.id=t.goal_id WHERE g.session_id=? ORDER BY t.created_at DESC LIMIT 50",session);}
    public void pause(String session,String id,long expectedRevision){tx.executeWithoutResult(status->{var goal=store.goal(session).orElseThrow();if(jdbc.update("UPDATE goal_triggers SET state='PAUSED',revision=revision+1 WHERE goal_id=? AND id=? AND revision=? AND state='ACTIVE'",goal.id(),id,expectedRevision)!=1)throw new IllegalStateException("触发器已改变，请刷新");programs.event(goal,"goal_trigger_paused",Map.of("id",id));});}
    public void poll(){
        var ids=jdbc.queryForList("SELECT id FROM goal_triggers WHERE state='ACTIVE' AND next_at<=? ORDER BY next_at,id LIMIT 20",String.class,System.currentTimeMillis());
        for(String id:ids)try{evaluate(id);}catch(RuntimeException unavailable){jdbc.update("UPDATE goal_triggers SET next_at=?,failures=failures+1,last_error='TRIGGER_UNAVAILABLE',state=CASE WHEN failures>=2 THEN 'FAILED' ELSE state END WHERE id=? AND state='ACTIVE'",System.currentTimeMillis()+30000,id);}
    }
    private void evaluate(String id){tx.executeWithoutResult(status->{
        var rows=jdbc.queryForList("SELECT t.*,g.session_id FROM goal_triggers t JOIN goals g ON g.id=t.goal_id WHERE t.id=? AND t.state='ACTIVE' FOR UPDATE OF t SKIP LOCKED",id);if(rows.isEmpty())return;var row=rows.get(0);String session=(String)row.get("session_id");jdbc.queryForObject("SELECT id FROM goals WHERE session_id=? FOR UPDATE",String.class,session);var goal=store.goal(session).orElseThrow();var p=programs.find(goal.id()).orElseThrow();var policy=decode((String)row.get("content"));long now=System.currentTimeMillis();
        if(now>=policy.until()||now>=p.policy().until()||((Number)row.get("activations")).intValue()>=policy.maxActivations()){stop(id,"EXHAUSTED");return;}
        if(p.revision()!=((Number)row.get("program_revision")).longValue()||goal.revision()!=((Number)row.get("goal_revision")).longValue()||p.goalRevision()!=goal.revision()){stop(id,"STALE");return;}
        if(goal.state()==GoalRecord.State.CLOSED){stop(id,"CLOSED");return;}
        if(goal.state()!=GoalRecord.State.ACTIVE){jdbc.update("UPDATE goal_triggers SET next_at=? WHERE id=?",now+30000,id);return;}
        String fingerprint=switch(policy.kind()){
            case AT_TIME -> now>=policy.at()?"time:"+policy.at():"";
            case RESOURCE_CHANGED -> resourceFingerprint(session);
            case EVIDENCE_EXPIRING -> expiringFingerprint(session,p,policy.stepIds(),now);
        };
        if(fingerprint.isEmpty()||fingerprint.equals(row.get("last_fingerprint"))||now-((Number)row.get("last_fired_at")).longValue()<policy.cooldownSeconds()*1000L){jdbc.update("UPDATE goal_triggers SET next_at=?,failures=0,last_error='' WHERE id=?",now+10000,id);return;}
        long revision=p.revision();
        if(policy.action()==Action.REFRESH_PREPARATION){if(!p.policy().enabled()){stop(id,"PAUSED");return;}if(!store.unresolvedTasks(session).isEmpty()){jdbc.update("UPDATE goal_triggers SET next_at=? WHERE id=?",now+30000,id);return;}var next=programs.refresh(session,p.revision(),policy.stepIds());revision=next.revision();wakeups.wake(session,"trigger_"+id.replace("-","")+"_"+fingerprint.substring(0,Math.min(20,fingerprint.length())).replaceAll("[^A-Za-z0-9_-]","_"));}
        String notice=JsonFingerprint.hash(Map.of("trigger",id,"event",fingerprint,"revision",revision));jdbc.update("INSERT INTO goal_notifications(id,goal_id,fingerprint,title,created_at) VALUES (?,?,?,?,?) ON CONFLICT(goal_id,fingerprint) DO NOTHING",UUID.randomUUID().toString(),goal.id(),notice,"触发器 "+policy.kind()+"："+(policy.action()==Action.NOTIFY?"请复核相关资料或资源":"已刷新确认范围内的准备步骤"),now);
        jdbc.update("UPDATE goal_triggers SET failures=0,last_error='',program_revision=?,revision=revision+1,activations=activations+1,last_fingerprint=?,last_fired_at=?,next_at=?,state=? WHERE id=?",revision,fingerprint,now,now+policy.cooldownSeconds()*1000L,policy.kind()==Kind.AT_TIME||((Number)row.get("activations")).intValue()+1>=policy.maxActivations()?"EXHAUSTED":"ACTIVE",id);programs.event(goal,"goal_trigger_fired",Map.of("id",id,"kind",policy.kind(),"action",policy.action(),"eventHash",fingerprint));
    });}
    private String expiringFingerprint(String session,GoalProgram p,Set<String> steps,long now){var keys=p.steps().stream().filter(s->steps.contains(s.id())).map(p::taskKey).collect(java.util.stream.Collectors.toSet());var facts=store.tasksForKeys(session,p.goalRevision(),keys).stream().flatMap(t->TaskFacts.of(t).stream()).filter(f->f.expiresAt()<=now+60000).sorted(Comparator.comparing(TaskFact::id)).map(f->Map.of("id",f.id(),"version",f.version(),"expiresAt",f.expiresAt())).toList();return facts.isEmpty()?"":JsonFingerprint.hash(facts);}
    private String resourceFingerprint(String session){return JsonFingerprint.hash(resources.list(session).stream().map(r->Map.of("operation",r.get("operation_id"),"observation",Objects.toString(r.get("observation_id"),""),"state",Objects.toString(r.get("state"),"UNKNOWN"))).toList());}
    private void stop(String id,String state){jdbc.update("UPDATE goal_triggers SET state=?,revision=revision+1 WHERE id=?",state,id);}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("触发器无效",e);}}
    private Request decode(String content){try{return json.readValue(content,Request.class);}catch(Exception e){throw new IllegalStateException("触发器内容不可读",e);}}
}
