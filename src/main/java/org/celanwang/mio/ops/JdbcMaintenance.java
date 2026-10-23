package org.celanwang.mio.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.celanwang.mio.orchestration.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Offline maintenance only. No Spring application, schema initialization, model or provider is started. */
public final class JdbcMaintenance {
    public enum Operation { RETRY_MEMORY_JOB, RECOVER_RESTORED_RUNTIME }
    public record Plan(int schemaVersion, Operation operation, String target, String digest, Map<String,Long> affected) {}
    private static final List<String> TABLES = List.of("execution_leases", "goals", "tasks", "task_queue",
            "goal_budgets", "budget_calls", "memory_jobs", "memory_usage_days", "memory_usage_calls", "operation_claims");
    private static final List<String> OPTIONAL_TABLES=List.of("goal_programs","goal_proposals","goal_wakeups","goal_resources","resource_observations","workspace_documents","goal_notifications","goal_deliveries","goal_windows","goal_context_archives","document_chunks","episode_vectors","resource_watches","schema_migrations","forgotten_episodes","document_index_jobs","goal_comparisons","goal_syntheses");
    private List<String> tables(){var result=new ArrayList<>(TABLES);for(String table:OPTIONAL_TABLES)if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL",Boolean.class,table)))result.add(table);return result;}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final JdbcCoordinationStore store;
    private final JdbcTaskQueue queue;
    private final JdbcGoalBudgetStore budgets;

    public JdbcMaintenance(JdbcTemplate jdbc, DataSource source, ObjectMapper json) {
        this.jdbc=jdbc; this.json=json;
        tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setTimeout(30);
        store=new JdbcCoordinationStore(jdbc,source,json);
        queue=new JdbcTaskQueue(jdbc,source,json,store,new JdbcExecutionLeaseStore(jdbc,source));
        budgets=new JdbcGoalBudgetStore(jdbc,source,json);
    }

    public Plan preview(Operation operation, String target) {
        var read=new TransactionTemplate(tx.getTransactionManager());
        read.setReadOnly(true); read.setTimeout(30);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return read.execute(status -> plan(operation,target));
    }

    public String apply(Operation operation, String target, String expectedDigest, String reason, boolean offline) {
        if(!offline) throw new IllegalArgumentException("执行维护前必须停止所有应用实例并显式声明 --offline");
        if(expectedDigest==null||!expectedDigest.matches("[a-f0-9]{64}")||reason==null||reason.isBlank()||reason.length()>500)
            throw new IllegalArgumentException("执行需要预览摘要及 1–500 字的维护原因");
        return tx.execute(status -> {
            jdbc.execute("SET LOCAL lock_timeout = '5s'");
            // Blocks writes and row-locking readers while fencing the restored identities. Live apps must be stopped.
            jdbc.execute("LOCK TABLE "+String.join(",",tables())+",events IN ACCESS EXCLUSIVE MODE");
            Plan current=plan(operation,target);
            if(!current.digest().equals(expectedDigest)) throw new IllegalStateException("数据库状态已改变，请重新预览");
            long now=jdbc.queryForObject("SELECT floor(extract(epoch FROM clock_timestamp())*1000)::bigint",Long.class);
            if(operation==Operation.RETRY_MEMORY_JOB) {
                jdbc.update("UPDATE memory_jobs SET state='RETRY',attempts=0,generation=generation+1,owner=NULL,lease_until=0,next_attempt_at=0,error_code='ADMIN_RETRY',updated_at=? WHERE id=? AND state='DEAD'",now,target);
            } else recover(now);
            String id="maintenance:"+UUID.randomUUID();
            jdbc.update("INSERT INTO events(id,ts,session_id,node,type,title,detail) VALUES (?,?,NULL,'maintenance','maintenance_applied','maintenance_applied',?)",
                    id,now,encode(Map.of("plan",current,"reason",reason.strip())));
            return id;
        });
    }

    private Plan plan(Operation operation, String target) {
        Objects.requireNonNull(operation,"维护操作缺失");
        if(target==null||target.length()>250||operation==Operation.RETRY_MEMORY_JOB&&target.isBlank()
                ||operation==Operation.RECOVER_RESTORED_RUNTIME&&!target.isEmpty()) throw new IllegalArgumentException("维护目标无效");
        var counts=new LinkedHashMap<String,Long>();
        if(operation==Operation.RETRY_MEMORY_JOB) {
            if(count("SELECT count(*) FROM memory_jobs WHERE id=? AND state='DEAD'",target)!=1)
                throw new IllegalStateException("只允许显式重试一条 DEAD 记忆作业");
            counts.put("memory_jobs",1L);
            counts.put("saved_checkpoints",count("SELECT count(*) FROM memory_jobs WHERE id=? AND result IS NOT NULL",target));
        } else {
            counts.put("leases",count("SELECT count(*) FROM execution_leases WHERE owner IS NOT NULL OR expires_at<>0"));
            counts.put("active_goals",count("SELECT count(*) FROM goals WHERE content::jsonb->>'state' IS NULL OR content::jsonb->>'state'='ACTIVE'"));
            counts.put("tasks",count("SELECT count(*) FROM tasks WHERE content::jsonb->>'status' IN ('RUNNING','AWAITING_APPROVAL')"));
            counts.put("memory_jobs",count("SELECT count(*) FROM memory_jobs WHERE state='RUNNING' AND (owner IS NOT NULL OR lease_until<>0)"));
            counts.put("goal_pending_usage",count("SELECT count(*) FROM budget_calls WHERE state='PENDING'"));
            counts.put("memory_pending_usage",count("SELECT count(*) FROM memory_usage_calls WHERE state='PENDING'"));
        }
        // Content is hashed inside the process and never printed. Database/schema bind a preview to its destination.
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            add(digest,"maintenance-v1"); add(digest,operation.name()); add(digest,target);
            add(digest,jdbc.execute((ConnectionCallback<String>) connection -> connection.getMetaData().getURL()));
            add(digest,jdbc.queryForObject("SELECT current_database()||':'||current_schema()||':'||current_user",String.class));
            for(String table:tables()) {
                add(digest,table);
                jdbc.query(connection -> {
                    var statement=connection.prepareStatement("SELECT to_jsonb(t)::text FROM "+table+" t ORDER BY to_jsonb(t)::text");
                    statement.setFetchSize(128);
                    return statement;
                },(RowCallbackHandler) rs -> add(digest,rs.getString(1)));
            }
            return new Plan(1,operation,target,HexFormat.of().formatHex(digest.digest()),Map.copyOf(counts));
        } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private void recover(long now) {
        if(tables().contains("document_index_jobs"))jdbc.update("UPDATE document_index_jobs SET state='PAUSED',lease_token=NULL,lease_until=0,last_error='RESTORE_STOPPED' WHERE state IN ('QUEUED','RUNNING')");
        if(tables().contains("goal_wakeups"))jdbc.update("UPDATE goal_wakeups SET state='FAILED',owner=NULL,lease_until=0,error_code='RESTORE_STOPPED' WHERE state IN ('READY','RUNNING')");
        if(tables().contains("goal_programs"))jdbc.update("UPDATE goal_programs SET revision=revision+1,content=jsonb_set(jsonb_set(content::jsonb,'{policy,enabled}','false'::jsonb),'{revision}',to_jsonb(revision+1))::text WHERE (content::jsonb->'policy'->>'enabled')::boolean=true");
        // Pause goals without touching user intent, acceptance conditions, receipts or account claims.
        for(String session:jdbc.query("SELECT session_id FROM goals ORDER BY id",(rs,n)->rs.getString(1))) {
            var goal=store.goal(session).orElseThrow();
            if(goal.state()==GoalRecord.State.ACTIVE) store.saveGoal(goal,goal.transition(GoalRecord.State.PAUSED,now));
            for(var task:store.tasks(session)) {
                if(task.status()==TaskStatus.RUNNING||task.status()==TaskStatus.AWAITING_APPROVAL) {
                    var state=task.status()==TaskStatus.AWAITING_APPROVAL?TaskStatus.STALE:task.effectStarted()?TaskStatus.UNKNOWN:TaskStatus.FAILED;
                    store.saveTask(task,task.next(state,"备份恢复：旧执行与批准失效，已发出操作仍需核查",List.of(),task.effectStarted(),null,now),"restore_task_stopped");
                }
            }
        }
        jdbc.update("UPDATE execution_leases SET owner=NULL,expires_at=0,generation=generation+1 WHERE owner IS NOT NULL OR expires_at<>0");
        for(String task:jdbc.query("SELECT task_id FROM task_queue ORDER BY task_id",(rs,n)->rs.getString(1))) queue.sync(task);
        // Keep attempts and checkpoint. The ordinary claim path decides exhaustion using the configured limit.
        jdbc.update("UPDATE memory_jobs SET owner=NULL,lease_until=0,generation=generation+1,updated_at=? WHERE state='RUNNING' AND (owner IS NOT NULL OR lease_until<>0)",now);
        for(var call:jdbc.queryForList("SELECT id,goal_id FROM budget_calls WHERE state='PENDING' ORDER BY id"))
            budgets.settle((String)call.get("goal_id"),(String)call.get("id"),null,false);
        for(String goal:jdbc.query("SELECT goal_id FROM goal_budgets",(rs,n)->rs.getString(1)))
            if(budgets.find(goal).orElseThrow().pendingUsage()!=0) throw new IllegalStateException("目标用量账目不一致，恢复已回滚");
        for(String day:jdbc.query("SELECT DISTINCT day FROM memory_usage_calls WHERE state='PENDING' ORDER BY day",(rs,n)->rs.getString(1))) {
            int changed=jdbc.update("UPDATE memory_usage_calls SET state='UNKNOWN' WHERE day=? AND state='PENDING'",day);
            if(jdbc.update("UPDATE memory_usage_days SET pending=pending-?,unknown=unknown+? WHERE day=? AND pending>=?",changed,changed,day,changed)!=1)
                throw new IllegalStateException("记忆用量账目不一致，恢复已回滚");
        }
        if(count("SELECT count(*) FROM memory_usage_days WHERE pending<>0")!=0)
            throw new IllegalStateException("记忆用量账目不一致，恢复已回滚");
    }

    private long count(String sql,Object...args) { return jdbc.queryForObject(sql,Long.class,args); }
    private String encode(Object value) { try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("维护审计序列化失败",e);} }
    private static void add(MessageDigest digest,String value) { digest.update(value.getBytes(StandardCharsets.UTF_8)); digest.update((byte)0); }
}
