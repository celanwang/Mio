package org.celanwang.mio.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.util.*;

/** PG is the only production backend. Critical state and its event share a transaction. */
public final class JdbcCoordinationStore implements CoordinationStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final java.time.Clock clock;
    private java.util.function.Consumer<TaskRecord> taskListener=task->{};
    public void onTaskChanged(java.util.function.Consumer<TaskRecord> listener){this.taskListener=Objects.requireNonNull(listener);}
    public JdbcCoordinationStore(JdbcTemplate jdbc, DataSource source, ObjectMapper json) {
        this(jdbc,source,json,java.time.Clock.systemUTC());
    }
    public JdbcCoordinationStore(JdbcTemplate jdbc,DataSource source,ObjectMapper json,java.time.Clock clock){
        this.jdbc=jdbc;this.json=json;this.clock=clock;transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
    }
    public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS goals (id TEXT PRIMARY KEY, session_id TEXT NOT NULL UNIQUE, revision BIGINT NOT NULL, content TEXT NOT NULL)");
        jdbc.execute("ALTER TABLE goals ADD COLUMN IF NOT EXISTS control_revision BIGINT NOT NULL DEFAULT 0");
        jdbc.execute("CREATE TABLE IF NOT EXISTS tasks (id TEXT PRIMARY KEY, session_id TEXT NOT NULL, goal_revision BIGINT NOT NULL, task_key TEXT NOT NULL, revision BIGINT NOT NULL, content TEXT NOT NULL, UNIQUE(session_id, goal_revision, task_key))");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_tasks_session ON tasks(session_id)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS operation_scopes(id TEXT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS operation_claims(scope_key TEXT NOT NULL REFERENCES operation_scopes(id), operation_id TEXT NOT NULL, task_id TEXT NOT NULL REFERENCES tasks(id), logical_id TEXT NOT NULL UNIQUE, PRIMARY KEY(scope_key,operation_id))");
    }
    private String encode(Object value) {
        try {return json.writeValueAsString(value);} catch(Exception e){throw new IllegalStateException("协作状态序列化失败",e);}
    }
    private <T> T decode(String raw, Class<T> type) {
        try {return json.readValue(raw,type);} catch(Exception e){throw new IllegalStateException("协作状态损坏，已停止执行",e);}
    }
    @Override public Optional<GoalRecord> goal(String session) {
        return jdbc.query("SELECT content FROM goals WHERE session_id = ?",(rs,n)->decode(rs.getString(1),GoalRecord.class),session).stream().findFirst();
    }
    @Override public void createGoal(GoalRecord goal) {
        transaction.executeWithoutResult(tx->{
            jdbc.update("INSERT INTO goals(id, session_id, revision, content, control_revision) VALUES (?, ?, ?, ?, ?)",goal.id(),goal.sessionId(),goal.revision(),encode(goal),goal.controlRevision());
            event(goal.sessionId(),"goal_created",goal.id()+":"+goal.revision(),goal);
        });
    }
    @Override public void saveGoal(GoalRecord expected, GoalRecord next) {
        if (!expected.id().equals(next.id()) || !expected.sessionId().equals(next.sessionId())
                || !(next.revision()==expected.revision()+1&&next.controlRevision()==expected.controlRevision()
                ||next.revision()==expected.revision()&&next.controlRevision()==expected.controlRevision()+1)) throw new IllegalArgumentException("目标版本不连续");
        transaction.executeWithoutResult(tx->{
            int changed=jdbc.update("UPDATE goals SET revision = ?, content = ?, control_revision = ? WHERE id = ? AND revision = ? AND control_revision = ?",
                    next.revision(),encode(next),next.controlRevision(),expected.id(),expected.revision(),expected.controlRevision());
            if(changed!=1)throw new IllegalStateException("目标已被其他请求修改");
            event(next.sessionId(),"goal_updated",next.id()+":"+next.revision()+":control:"+next.controlRevision(),next);
        });
    }
    @Override public List<TaskRecord> tasks(String session) {
        return jdbc.query("SELECT content FROM tasks WHERE session_id = ? ORDER BY id",(rs,n)->decode(rs.getString(1),TaskRecord.class),session);
    }
    @Override public long activeTaskCount(String session){return jdbc.queryForObject("SELECT count(*) FROM tasks WHERE session_id=? AND content::jsonb->>'status' NOT IN ('COMPLETED','FAILED','CANCELLED','STALE','UNKNOWN')",Long.class,session);}
    @Override public Optional<TaskRecord> task(String id) {
        return jdbc.query("SELECT content FROM tasks WHERE id = ?",(rs,n)->decode(rs.getString(1),TaskRecord.class),id).stream().findFirst();
    }
    @Override public void createTask(TaskRecord task) {
        transaction.executeWithoutResult(tx->{
            jdbc.update("INSERT INTO tasks(id, session_id, goal_revision, task_key, revision, content) VALUES (?, ?, ?, ?, ?, ?)",
                    task.id(),task.sessionId(),task.goalRevision(),task.spec().key(),task.revision(),encode(task));
            event(task.sessionId(),"task_created",task.id()+":"+task.revision(),task);
        });
    }
    @Override public void saveTask(TaskRecord expected, TaskRecord next, String type) {
        if(!expected.id().equals(next.id())||next.revision()!=expected.revision()+1)throw new IllegalArgumentException("任务版本不连续");
        transaction.executeWithoutResult(tx->{
            int changed=jdbc.update("UPDATE tasks SET revision = ?, content = ?, goal_revision = ? WHERE id = ? AND revision = ?",
                    next.revision(),encode(next),next.goalRevision(),next.id(),expected.revision());
            if(changed!=1)throw new IllegalStateException("任务已被取消或修改");
            event(next.sessionId(),type,next.id()+":"+next.revision(),next);
            taskListener.accept(next);
        });
    }
    @Override public void startOperation(TaskRecord expected, OperationRecord operation, long now) {
        transaction.executeWithoutResult(tx->{
            lockScope(operation.scopeKey());
            if(!operation.scopeKey().isEmpty()&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM operation_claims WHERE scope_key=?)",Boolean.class,operation.scopeKey())))throw new IllegalStateException("该账号范围存在未核实操作，禁止跨会话重复提交");
            Long version=jdbc.queryForObject("SELECT revision FROM goals WHERE session_id = ? FOR UPDATE",Long.class,expected.sessionId());
            if(version==null||version!=expected.goalRevision())throw new IllegalStateException("目标已改变，禁止提交旧操作");
            goal(expected.sessionId()).orElseThrow().requireActive();
            TaskDependencies.requireReady(this,expected,now);
            for(String source:TaskDependencies.ancestors(this,expected))
                jdbc.queryForObject("SELECT id FROM tasks WHERE id = ? FOR UPDATE",String.class,source);
            // Row-lock waits must not freeze the fact's validity at request arrival time.
            long checkedAt=Math.max(now,clock.millis());
            TaskFacts.bind(this,expected,checkedAt);
            if(tasks(expected.sessionId()).stream().anyMatch(t->t.spec().capabilityId().equals(expected.spec().capabilityId())&&t.unresolvedEffects()))
                throw new IllegalStateException("同领域已有未核查提交，禁止重复操作");
            saveTask(expected,expected.withOperation(operation,checkedAt),"operation_started");
            if(!operation.scopeKey().isEmpty())jdbc.update("INSERT INTO operation_claims(scope_key,operation_id,task_id,logical_id) VALUES (?,?,?,?)",operation.scopeKey(),operation.id(),expected.id(),operation.logicalId());
        });
    }
    @Override public void recordReceipt(String taskId,String operationId,OperationReceipt receipt,long now){
        transaction.executeWithoutResult(tx->{
            var original=task(taskId).orElseThrow().operations().stream().filter(o->o.id().equals(operationId)).findFirst().orElseThrow();
            lockScope(original.scopeKey());
            var current=jdbc.queryForObject("SELECT content FROM tasks WHERE id = ? FOR UPDATE",(rs,n)->decode(rs.getString(1),TaskRecord.class),taskId);
            var next=current.withReceipt(operationId,receipt,now);
            if(next!=current){
                saveTask(current,next,"operation_receipt");
                var op=next.operations().stream().filter(o->o.id().equals(operationId)).findFirst().orElseThrow();
                if(!op.scopeKey().isEmpty()){
                    if(op.outcome()==OperationOutcome.UNKNOWN)jdbc.update("INSERT INTO operation_claims(scope_key,operation_id,task_id,logical_id) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",op.scopeKey(),op.id(),taskId,op.logicalId());
                    else jdbc.update("DELETE FROM operation_claims WHERE scope_key=? AND operation_id=?",op.scopeKey(),op.id());
                }
            }
        });
    }
    @Override public TaskRecord invalidateDependencies(String taskId,long now){
        return transaction.execute(tx->{
            var original=task(taskId).orElseThrow();
            jdbc.queryForObject("SELECT revision FROM goals WHERE session_id=? FOR UPDATE",Long.class,original.sessionId());
            var current=jdbc.queryForObject("SELECT content FROM tasks WHERE id=? FOR UPDATE",(rs,n)->decode(rs.getString(1),TaskRecord.class),taskId);
            var cause=TaskDependencies.invalidation(this,current,now);
            if(cause.isEmpty()||current.invalidation()!=null)return current;
            var next=current.invalidate(cause.get(),now);saveTask(current,next,"task_dependency_invalidated");return next;
        });
    }
    @Override public void recordObservation(String taskId,FactObservation observation,boolean starting,long now){
        transaction.executeWithoutResult(tx->{
            var original=task(taskId).orElseThrow();
            Long version=jdbc.queryForObject("SELECT revision FROM goals WHERE session_id=? FOR UPDATE",Long.class,original.sessionId());
            var current=jdbc.queryForObject("SELECT content FROM tasks WHERE id=? FOR UPDATE",(rs,n)->decode(rs.getString(1),TaskRecord.class),taskId);
            if(version==null||version!=current.goalRevision())throw new IllegalStateException("目标已改变，事实观测失效");
            goal(current.sessionId()).orElseThrow().requireActive();
            saveTask(current,current.withObservation(observation,starting,now),starting?"fact_observation_started":"fact_observation_returned");
        });
    }
    private void lockScope(String scope){
        if(scope.isEmpty())return;
        jdbc.update("INSERT INTO operation_scopes(id) VALUES (?) ON CONFLICT DO NOTHING",scope);
        jdbc.queryForObject("SELECT id FROM operation_scopes WHERE id=? FOR UPDATE",String.class,scope);
    }
    private void event(String session, String type, String id, Object value) {
        jdbc.update("INSERT INTO events(id, ts, session_id, node, type, title, detail) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "coordination:"+id,System.currentTimeMillis(),session,"coordination",type,type,encode(value));
    }
}
