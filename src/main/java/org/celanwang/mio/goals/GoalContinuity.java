package org.celanwang.mio.goals;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.celanwang.mio.orchestration.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;

/** Explicit execution-window renewal and user-confirmed context checkpoints. Budgets and receipts stay intact. */
public final class GoalContinuity {
    public record Renew(long expectedRevision,long until,int additionalActivations,String reason,int modelCalls,int toolCalls,int taskCalls,long tokens){
        public Renew(long expectedRevision,long until,int additionalActivations,String reason){this(expectedRevision,until,additionalActivations,reason,0,0,0,0);}
    }
    public record Context(long expectedGoalRevision,String summary,String reason){}
    private final JdbcTemplate jdbc;private final CoordinationStore store;private final JdbcGoalPrograms programs;private final ExecutionLeaseStore leases;private final ObjectMapper json;private final TransactionTemplate tx;
    public GoalContinuity(JdbcTemplate jdbc,DataSource source,CoordinationStore store,JdbcGoalPrograms programs,ExecutionLeaseStore leases,ObjectMapper json){this.jdbc=jdbc;this.store=store;this.programs=programs;this.leases=leases;this.json=json;tx=new TransactionTemplate(new DataSourceTransactionManager(source));}
    public void initialize(){jdbc.execute("CREATE TABLE IF NOT EXISTS goal_windows(id TEXT PRIMARY KEY,goal_id TEXT NOT NULL REFERENCES goals(id),program_revision BIGINT NOT NULL,content TEXT NOT NULL,created_at BIGINT NOT NULL)");jdbc.execute("CREATE TABLE IF NOT EXISTS goal_context_archives(goal_id TEXT NOT NULL REFERENCES goals(id),revision BIGINT NOT NULL,content TEXT NOT NULL,reason TEXT NOT NULL,created_at BIGINT NOT NULL,PRIMARY KEY(goal_id,revision))");}
    public GoalProgram renew(String session,Renew request){return fenced(session,()->{
        reason(request.reason());var goal=store.goal(session).orElseThrow();goal.requireActive();var p=programs.find(goal.id()).orElseThrow();if(p.revision()!=request.expectedRevision()||request.additionalActivations()<0||request.additionalActivations()>100)throw new IllegalArgumentException("续期版本或新增次数无效");
        var fields=new HashMap<String,String>();p.fields().forEach((k,v)->fields.put(k,v.value()));var policy=p.policy();var next=programs.save(session,new GoalProgram.Request(p.revision(),goal.revision(),fields,p.steps(),new GoalProgram.Policy(policy.enabled(),request.until(),Math.addExact(policy.maxActivations(),request.additionalActivations()),policy.intervalSeconds(),policy.parallelism(),policy.capabilities(),policy.trackResources())));
        if(request.modelCalls()<0||request.modelCalls()>100||request.toolCalls()<0||request.toolCalls()>200||request.taskCalls()<0||request.taskCalls()>100||request.tokens()<0||request.tokens()>200000)throw new IllegalArgumentException("新增预算范围无效");
        if(request.modelCalls()+request.toolCalls()+request.taskCalls()+request.tokens()>0){var rows=jdbc.queryForList("SELECT content FROM goal_budgets WHERE goal_id=? FOR UPDATE",goal.id());if(rows.isEmpty())throw new IllegalStateException("请先建立目标预算再追加额度");try{var old=json.readValue((String)rows.get(0).get("content"),GoalBudgetStore.Snapshot.class);var l=old.limits();var limits=new GoalBudgetStore.Limits(Math.addExact(l.models(),request.modelCalls()),Math.addExact(l.tools(),request.toolCalls()),Math.addExact(l.tasks(),request.taskCalls()),Math.addExact(l.tokens(),request.tokens()));var updated=new GoalBudgetStore.Snapshot(goal.id(),limits,old.models(),old.tools(),old.tasks(),old.knownTokens(),old.pendingUsage(),old.unknownUsage(),old.revision()+1,old.historyUnknown());jdbc.update("UPDATE goal_budgets SET content=? WHERE goal_id=?",encode(updated),goal.id());programs.event(goal,"goal_budget_extended",Map.of("previous",old,"current",updated,"reason",request.reason()));}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("目标预算无法读取",e);}}
        jdbc.update("INSERT INTO goal_windows VALUES (?,?,?,?,?)",UUID.randomUUID().toString(),goal.id(),next.revision(),encode(request),System.currentTimeMillis());programs.event(goal,"goal_window_renewed",request);return next;
    });}
    public void checkpoint(String session,Context request){fenced(session,()->{
        reason(request.reason());if(request.summary()==null||request.summary().isBlank()||request.summary().length()>12000)throw new IllegalArgumentException("请确认本阶段完整有效的目标摘要，最多12000字符");var goal=store.goal(session).orElseThrow();if(goal.state()==GoalRecord.State.CLOSED||goal.revision()!=request.expectedGoalRevision())throw new IllegalStateException("目标已改变或关闭");
        jdbc.update("INSERT INTO goal_context_archives VALUES (?,?,?,?,?)",goal.id(),goal.revision(),encode(goal),request.reason(),System.currentTimeMillis());long now=System.currentTimeMillis();
        var next=new GoalRecord(goal.id(),session,goal.revision()+1,request.summary(),request.summary(),goal.createdAt(),now,null,List.of(new GoalRecord.UserTurn(goal.revision()+1,request.summary())),goal.state(),goal.controlRevision());store.saveGoal(goal,next);
        for(var task:store.tasks(session))if(!task.status().terminal()&&task.status()!=TaskStatus.RUNNING)store.saveTask(task,task.invalidate(new TaskInvalidation(TaskInvalidation.Code.FACT_CHANGED,task.id(),task.id(),"","context_checkpoint",now),now),"context_checkpoint_invalidated");
        programs.event(next,"goal_context_confirmed",Map.of("previousRevision",goal.revision(),"reason",request.reason()));return null;
    });}
    public List<Map<String,Object>> history(String session,String before,int limit){if(limit<1||limit>100)throw new IllegalArgumentException("分页大小无效");return jdbc.queryForList("SELECT id,revision,goal_revision,content::jsonb->>'status' AS status,content::jsonb->'spec'->>'objective' AS objective,content::jsonb->>'updatedAt' AS updated_at FROM tasks WHERE session_id=? AND (?='' OR id<?) ORDER BY id DESC LIMIT ?",session,before,before,limit);}
    public List<Map<String,Object>> archives(String session){return jdbc.queryForList("SELECT revision,content,reason,created_at FROM goal_context_archives WHERE goal_id=? ORDER BY revision DESC",store.goal(session).orElseThrow().id());}
    private <T>T fenced(String session,java.util.function.Supplier<T> action){var token=leases.acquire(session,15000);try{return leases.fenced(token,()->tx.execute(status->{jdbc.queryForObject("SELECT id FROM goals WHERE session_id=? FOR UPDATE",String.class,session);return action.get();}));}finally{leases.release(token);}}
    private void reason(String text){if(text==null||text.isBlank()||text.length()>500)throw new IllegalArgumentException("请填写操作原因");}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("上下文编码失败",e);}}
}
