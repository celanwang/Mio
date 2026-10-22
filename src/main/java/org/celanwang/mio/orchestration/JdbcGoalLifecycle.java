package org.celanwang.mio.orchestration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.List;

/** Lock order: execution lease, goal, tasks, queue. Pausing can revoke a running foreground owner. */
public final class JdbcGoalLifecycle implements GoalLifecycle {
    private final JdbcTemplate jdbc;private final CoordinationStore store;private final TaskQueue queue;private final TransactionTemplate tx;
    public JdbcGoalLifecycle(JdbcTemplate jdbc,DataSource source,CoordinationStore store,TaskQueue queue){this.jdbc=jdbc;this.store=store;this.queue=queue;tx=new TransactionTemplate(new DataSourceTransactionManager(source));}
    @Override public GoalRecord transition(String session,long expected,GoalRecord.State target){
        if(session==null||session.isBlank()||session.length()>200||expected<0||target==null)throw new IllegalArgumentException("目标控制参数无效");
        return tx.execute(status->{
            String resource=ExecutionLeaseStore.resource(session);
            jdbc.update("INSERT INTO execution_leases(resource,owner,generation,expires_at,session_id) VALUES (?,NULL,0,0,?) ON CONFLICT DO NOTHING",resource,session);
            jdbc.queryForObject("SELECT resource FROM execution_leases WHERE resource=? FOR UPDATE",String.class,resource);
            jdbc.queryForObject("SELECT id FROM goals WHERE session_id=? FOR UPDATE",String.class,session);
            var goal=store.goal(session).orElseThrow();
            if(goal.controlRevision()!=expected)throw new IllegalStateException("目标控制状态已改变，请刷新后操作");
            var next=goal.transition(target,System.currentTimeMillis());if(next==goal)return goal;
            store.saveGoal(goal,next);
            var revoked=jdbc.query("UPDATE execution_leases SET owner=NULL,expires_at=0,generation=generation+1 WHERE resource=? AND owner IS NOT NULL RETURNING generation",(rs,n)->rs.getLong(1),resource);
            if(!revoked.isEmpty())jdbc.update("INSERT INTO events(id,ts,session_id,node,type,title,detail) VALUES (?,?,?,?,?,?,?)",
                    "lease:"+resource+":"+revoked.get(0)+":lease_revoked",System.currentTimeMillis(),session,"coordination","lease_revoked","lease_revoked",
                    "goal="+goal.id()+", controlRevision="+next.controlRevision()+", retiredGeneration="+(revoked.get(0)-1));
            if(target!=GoalRecord.State.ACTIVE){
                for(var original:store.tasks(session)){
                    jdbc.queryForObject("SELECT id FROM tasks WHERE id=? FOR UPDATE",String.class,original.id());
                    var task=store.task(original.id()).orElseThrow();if(task.status().terminal())continue;
                    TaskStatus state=task.status()==TaskStatus.RUNNING?(task.effectStarted()?TaskStatus.UNKNOWN:TaskStatus.FAILED):
                            task.status()==TaskStatus.AWAITING_APPROVAL?TaskStatus.STALE:target==GoalRecord.State.CLOSED?TaskStatus.CANCELLED:null;
                    if(state==null)continue;
                    var ended=task.next(state,task.effectStarted()?"目标已停止，已发出的操作仍需核查":"目标已"+(target==GoalRecord.State.CLOSED?"关闭":"暂停")+"，本次执行或批准不再恢复",List.of(),task.effectStarted(),null,System.currentTimeMillis());
                    store.saveTask(task,ended,"goal_task_stopped");
                    if(task.spec().dispatch()==TaskSpec.Dispatch.QUEUED)queue.sync(task.id());
                }
            }
            return next;
        });
    }
}
