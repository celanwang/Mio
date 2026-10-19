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
    public JdbcCoordinationStore(JdbcTemplate jdbc, DataSource source, ObjectMapper json) {
        this.jdbc=jdbc;this.json=json;transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
    }
    public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS goals (id TEXT PRIMARY KEY, session_id TEXT NOT NULL UNIQUE, revision BIGINT NOT NULL, content TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS tasks (id TEXT PRIMARY KEY, session_id TEXT NOT NULL, goal_revision BIGINT NOT NULL, task_key TEXT NOT NULL, revision BIGINT NOT NULL, content TEXT NOT NULL, UNIQUE(session_id, goal_revision, task_key))");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_tasks_session ON tasks(session_id)");
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
            jdbc.update("INSERT INTO goals(id, session_id, revision, content) VALUES (?, ?, ?, ?)",goal.id(),goal.sessionId(),goal.revision(),encode(goal));
            event(goal.sessionId(),"goal_created",goal.id()+":"+goal.revision(),goal);
        });
    }
    @Override public void saveGoal(GoalRecord expected, GoalRecord next) {
        if (!expected.id().equals(next.id()) || next.revision()!=expected.revision()+1) throw new IllegalArgumentException("目标版本不连续");
        transaction.executeWithoutResult(tx->{
            int changed=jdbc.update("UPDATE goals SET revision = ?, content = ? WHERE id = ? AND revision = ?",
                    next.revision(),encode(next),expected.id(),expected.revision());
            if(changed!=1)throw new IllegalStateException("目标已被其他请求修改");
            event(next.sessionId(),"goal_updated",next.id()+":"+next.revision(),next);
        });
    }
    @Override public List<TaskRecord> tasks(String session) {
        return jdbc.query("SELECT content FROM tasks WHERE session_id = ? ORDER BY id",(rs,n)->decode(rs.getString(1),TaskRecord.class),session);
    }
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
            int changed=jdbc.update("UPDATE tasks SET revision = ?, content = ? WHERE id = ? AND revision = ?",
                    next.revision(),encode(next),next.id(),expected.revision());
            if(changed!=1)throw new IllegalStateException("任务已被取消或修改");
            event(next.sessionId(),type,next.id()+":"+next.revision(),next);
        });
    }
    private void event(String session, String type, String id, Object value) {
        jdbc.update("INSERT INTO events(id, ts, session_id, node, type, title, detail) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "coordination:"+id,System.currentTimeMillis(),session,"coordination",type,type,encode(value));
    }
}
