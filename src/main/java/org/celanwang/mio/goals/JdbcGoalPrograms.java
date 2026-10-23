package org.celanwang.mio.goals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.celanwang.mio.orchestration.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;

public final class JdbcGoalPrograms {
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final TransactionTemplate tx;private final CoordinationStore store;private final AgentRegistry registry;
    public JdbcGoalPrograms(JdbcTemplate jdbc,DataSource source,ObjectMapper json,CoordinationStore store,AgentRegistry registry){this.jdbc=jdbc;this.json=json;this.store=store;this.registry=registry;tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(10);}
    public void initialize(){
        jdbc.execute("CREATE TABLE IF NOT EXISTS goal_programs(goal_id TEXT PRIMARY KEY REFERENCES goals(id),revision BIGINT NOT NULL,content TEXT NOT NULL,activations INTEGER NOT NULL DEFAULT 0,next_at BIGINT NOT NULL DEFAULT 0,last_error TEXT NOT NULL DEFAULT '')");
        jdbc.execute("CREATE TABLE IF NOT EXISTS goal_proposals(goal_id TEXT PRIMARY KEY REFERENCES goals(id),content TEXT NOT NULL,created_at BIGINT NOT NULL)");
    }
    public Optional<GoalProgram> find(String goal){return jdbc.query("SELECT content FROM goal_programs WHERE goal_id=?",(r,n)->decode(r.getString(1),GoalProgram.class),goal).stream().findFirst();}
    public Map<String,Object> view(String session){var goal=store.goal(session).orElseThrow(()->new IllegalArgumentException("请先创建对话目标"));var result=new LinkedHashMap<String,Object>();result.put("goal",goal);result.put("program",find(goal.id()).orElse(null));result.put("proposal",jdbc.query("SELECT content FROM goal_proposals WHERE goal_id=?",(r,n)->decode(r.getString(1),GoalProgram.Request.class),goal.id()).stream().findFirst().orElse(null));result.put("runtime",jdbc.queryForList("SELECT activations,next_at,last_error FROM goal_programs WHERE goal_id=?",goal.id()));return result;}
    public GoalProgram save(String session,GoalProgram.Request request){return tx.execute(status->{
        var goal=lockGoal(session);if(goal.state()==GoalRecord.State.CLOSED)throw new IllegalStateException("目标已关闭");if(goal.revision()!=request.expectedGoalRevision())throw new IllegalStateException("用户目标已改变，请刷新计划");
        var previous=find(goal.id()).orElse(null);long expected=previous==null?0:previous.revision();if(expected!=request.expectedRevision())throw new IllegalStateException("计划已被修改，请刷新");
        var next=build(goal,previous,request);var affected=new HashSet<String>();
        if(previous!=null)for(var step:previous.steps())if(!Objects.equals(previous.generations().get(step.id()),next.generations().get(step.id())))affected.add(previous.taskKey(step));
        for(var task:store.tasks(session))if(affected.contains(task.spec().key())&&task.invalidation()==null){
            if(task.status()==TaskStatus.RUNNING)throw new IllegalStateException("准备任务仍在运行，请先暂停目标再修改");
            store.saveTask(task,task.invalidate(new TaskInvalidation(TaskInvalidation.Code.FACT_CHANGED,task.id(),task.id(),"","goal_fields",System.currentTimeMillis()),System.currentTimeMillis()),"program_step_invalidated");
        }
        jdbc.update("INSERT INTO goal_programs(goal_id,revision,content,next_at) VALUES (?,?,?,0) ON CONFLICT(goal_id) DO UPDATE SET revision=EXCLUDED.revision,content=EXCLUDED.content,next_at=0,last_error=''",goal.id(),next.revision(),encode(next));
        jdbc.update("DELETE FROM goal_proposals WHERE goal_id=?",goal.id());event(goal,"goal_program_saved",Map.of("revision",next.revision(),"affected",affected));return next;
    });}
    private GoalProgram build(GoalRecord goal,GoalProgram previous,GoalProgram.Request request){
        if(request.fields()==null||request.steps()==null||request.policy()==null||request.fields().size()>20||request.steps().size()>12)throw new IllegalArgumentException("请提供最多20个字段、12个步骤和执行范围");
        long now=System.currentTimeMillis();if(request.policy().until()<=now||request.policy().until()>now+7*86400000L)throw new IllegalArgumentException("执行期限应在未来七天内");
        var fields=new LinkedHashMap<String,GoalProgram.Field>();request.fields().forEach((key,value)->{var old=previous==null?null:previous.fields().get(key);fields.put(key,new GoalProgram.Field(value,old==null?1:old.value().equals(value)?old.revision():old.revision()+1));});
        var changed=new HashSet<String>();if(previous!=null){var names=new HashSet<>(previous.fields().keySet());names.addAll(fields.keySet());for(String name:names)if(!Objects.equals(previous.fields().get(name),fields.get(name)))changed.add(name);}
        var affected=new HashSet<String>();for(var step:request.steps()){
            var old=previous==null?null:previous.steps().stream().filter(s->s.id().equals(step.id())).findFirst().orElse(null);
            if(old==null||!old.equals(step)||step.fields().isEmpty()&&!changed.isEmpty()||!Collections.disjoint(step.fields(),changed)||previous.goalRevision()!=goal.revision())affected.add(step.id());
            var bindings=step.inputs().entrySet().stream().map(entry->{var parts=entry.getValue().split("/",2);return new InputBinding(parts[0],parts[1],"planned",entry.getKey());}).toList();
            var spec=new TaskSpec("planned_"+UUID.randomUUID(),step.capabilityId(),step.objective(),List.of(),step.dependencies(),TaskScope.PREPARE,bindings,step.inputContract(),TaskSpec.Dispatch.QUEUED,step.outputContract());var capability=registry.require(step.capabilityId());capability.validateSpec(spec);
            var contract=capability.outputContract(spec);for(var check:step.checks())check.validate(contract.fields().stream().filter(f->f.id().equals(check.fieldId())).findFirst().orElseThrow(()->new IllegalArgumentException("验收字段不存在")));
        }
        boolean expanded;do{expanded=false;for(var step:request.steps())if(!affected.contains(step.id())&&!Collections.disjoint(step.dependencies(),affected))expanded|=affected.add(step.id());}while(expanded);
        var generations=previous==null?new HashMap<String,Long>():new HashMap<>(previous.generations());
        if(previous!=null)for(var old:previous.steps())if(request.steps().stream().noneMatch(s->s.id().equals(old.id())))generations.put(old.id(),previous.generations().get(old.id())+1);
        for(var step:request.steps()){long old=previous==null?0:previous.generations().getOrDefault(step.id(),0L);generations.put(step.id(),old==0?1:affected.contains(step.id())?old+1:old);}
        var next=new GoalProgram(goal.id(),goal.revision(),previous==null?1:previous.revision()+1,fields,request.steps(),request.policy(),generations);for(var step:next.steps())next.task(step,List.of());return next;
    }
    public void propose(String session,GoalProgram.Request request){tx.executeWithoutResult(status->{var goal=lockGoal(session);goal.requireActive();build(goal,find(goal.id()).orElse(null),request);jdbc.update("INSERT INTO goal_proposals VALUES (?,?,?) ON CONFLICT(goal_id) DO UPDATE SET content=EXCLUDED.content,created_at=EXCLUDED.created_at",goal.id(),encode(request),System.currentTimeMillis());event(goal,"goal_program_proposed",Map.of("steps",request.steps().size()));});}
    public GoalProgram refresh(String session,long expected,Set<String> roots){return tx.execute(status->{
        var goal=lockGoal(session);if(goal.state()==GoalRecord.State.CLOSED)throw new IllegalStateException("目标已关闭");var previous=find(goal.id()).orElseThrow();if(previous.revision()!=expected||previous.goalRevision()!=goal.revision())throw new IllegalStateException("计划或用户目标已更新，请重新确认计划");
        var affected=new HashSet<>(roots);if(affected.isEmpty()||!previous.steps().stream().map(GoalProgram.Step::id).collect(java.util.stream.Collectors.toSet()).containsAll(affected))throw new IllegalArgumentException("请选择现有步骤");
        boolean expanded;do{expanded=false;for(var step:previous.steps())if(!affected.contains(step.id())&&!Collections.disjoint(step.dependencies(),affected))expanded|=affected.add(step.id());}while(expanded);
        var keys=previous.steps().stream().filter(s->affected.contains(s.id())).map(previous::taskKey).collect(java.util.stream.Collectors.toSet());
        for(var task:store.tasks(session))if(keys.contains(task.spec().key())&&task.invalidation()==null){if(task.status()==TaskStatus.RUNNING)throw new IllegalStateException("步骤仍在运行");store.saveTask(task,task.invalidate(new TaskInvalidation(TaskInvalidation.Code.FACT_CHANGED,task.id(),task.id(),"","refresh",System.currentTimeMillis()),System.currentTimeMillis()),"program_step_refreshed");}
        var generations=new HashMap<>(previous.generations());affected.forEach(id->generations.put(id,generations.get(id)+1));
        var next=new GoalProgram(previous.goalId(),previous.goalRevision(),previous.revision()+1,previous.fields(),previous.steps(),previous.policy(),generations);
        jdbc.update("UPDATE goal_programs SET revision=?,content=?,next_at=0 WHERE goal_id=?",next.revision(),encode(next),goal.id());event(goal,"goal_program_refreshed",Map.of("steps",affected,"revision",next.revision()));return next;
    });}
    private GoalRecord lockGoal(String session){jdbc.queryForObject("SELECT id FROM goals WHERE session_id=? FOR UPDATE",String.class,session);return store.goal(session).orElseThrow();}
    public void event(GoalRecord goal,String type,Object detail){jdbc.update("INSERT INTO events(id,ts,session_id,node,type,title,detail) VALUES (?,?,?,?,?,?,?)","program:"+UUID.randomUUID(),System.currentTimeMillis(),goal.sessionId(),"goal",type,type,encode(detail));}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("计划编码失败",e);}}
    private <T>T decode(String value,Class<T> type){try{return json.readValue(value,type);}catch(Exception e){throw new IllegalStateException("计划内容损坏",e);}}
}
