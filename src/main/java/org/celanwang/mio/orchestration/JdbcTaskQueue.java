package org.celanwang.mio.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;

/** Targeted claims lock task then dispatch row. No provider call or model runs inside these transactions. */
public final class JdbcTaskQueue implements TaskQueue {
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final CoordinationStore store;private final ExecutionLeaseStore leases;
    private final TransactionTemplate tx;
    public JdbcTaskQueue(JdbcTemplate jdbc,DataSource source,ObjectMapper json,CoordinationStore store,ExecutionLeaseStore leases){
        this.jdbc=jdbc;this.json=json;this.store=store;this.leases=leases;tx=new TransactionTemplate(new DataSourceTransactionManager(source));
    }
    public void initialize(){
        jdbc.execute("CREATE TABLE IF NOT EXISTS task_queue(task_id TEXT PRIMARY KEY REFERENCES tasks(id),session_id TEXT NOT NULL,state TEXT NOT NULL,revision BIGINT NOT NULL,content TEXT NOT NULL)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_task_queue_session ON task_queue(session_id,state)");
    }
    private long now(){return jdbc.queryForObject("SELECT floor(extract(epoch from clock_timestamp())*1000)::bigint",Long.class);}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception failure){throw new IllegalStateException("队列序列化失败",failure);}}
    private Entry decode(String raw){try{return json.readValue(raw,Entry.class);}catch(Exception failure){throw new IllegalStateException("队列状态损坏，停止领取",failure);}}
    private Entry row(java.sql.ResultSet rs)throws java.sql.SQLException{
        var entry=decode(rs.getString("content"));
        if(!entry.taskId().equals(rs.getString("task_id"))||!entry.sessionId().equals(rs.getString("session_id"))||!entry.state().name().equals(rs.getString("state"))||entry.revision()!=rs.getLong("revision"))throw new IllegalStateException("队列索引与内容不一致");
        return entry;
    }
    @Override public Optional<Entry> find(String id){return jdbc.query("SELECT task_id,session_id,state,revision,content FROM task_queue WHERE task_id=?",(rs,n)->row(rs),id).stream().findFirst();}
    private TaskRecord lockTask(String id){jdbc.queryForObject("SELECT id FROM tasks WHERE id=? FOR UPDATE",String.class,id);return store.task(id).orElseThrow();}
    private Entry lockEntry(String id){return jdbc.queryForObject("SELECT task_id,session_id,state,revision,content FROM task_queue WHERE task_id=? FOR UPDATE",(rs,n)->row(rs),id);}
    private void event(Entry entry,String type){jdbc.update("INSERT INTO events(id,ts,session_id,node,type,title,detail) VALUES (?,?,?,?,?,?,?)",
            "queue:"+entry.taskId()+":"+entry.revision(),entry.updatedAt(),entry.sessionId(),"coordination",type,type,encode(View.of(entry)));}
    private Entry change(Entry old,State state,int attempts,String claim,ExecutionLeaseStore.Token lease,String reason,String event){
        var next=new Entry(old.taskId(),old.sessionId(),state,attempts,old.revision()+1,claim,lease,reason,now());
        if(jdbc.update("UPDATE task_queue SET state=?,revision=?,content=? WHERE task_id=? AND revision=?",state.name(),next.revision(),encode(next),old.taskId(),old.revision())!=1)
            throw new IllegalStateException("队列已被其他请求修改");
        event(next,event);return next;
    }
    @Override public void enqueue(TaskRecord task){
        if(task.status()!=TaskStatus.QUEUED||task.spec().dispatch()!=TaskSpec.Dispatch.QUEUED||task.spec().scope()!=TaskScope.PREPARE||task.effectStarted())throw new IllegalArgumentException("只能排队尚未执行的准备任务");
        tx.executeWithoutResult(status->{
            store.createTask(task);var entry=new Entry(task.id(),task.sessionId(),State.READY,0,0,"",null,"ENQUEUED",now());
            jdbc.update("INSERT INTO task_queue(task_id,session_id,state,revision,content) VALUES (?,?,?,?,?)",task.id(),task.sessionId(),entry.state().name(),0,encode(entry));event(entry,"task_enqueued");
        });
    }
    @Override public Optional<Claim> claim(String id,ExecutionLeaseStore.Token token){
        if(token==null)throw new IllegalArgumentException("队列领取缺少执行身份");
        return leases.fenced(token,()->tx.execute(status->{
            var task=lockTask(id);var entry=lockEntry(id);
            if(token==null||!token.resource().equals(ExecutionLeaseStore.resource(task.sessionId())))throw new IllegalArgumentException("队列领取身份不属于当前会话");
            leases.require(token);entry=syncLocked(task,entry);
            if(task.status()!=TaskStatus.QUEUED||entry.state().terminal())return Optional.empty();
            if(entry.state()!=State.READY)throw new IllegalStateException("队列任务已被领取或启动");
            if(task.spec().scope()!=TaskScope.PREPARE||task.spec().dispatch()!=TaskSpec.Dispatch.QUEUED||task.effectStarted())throw new IllegalStateException("队列执行范围无效");
            if(store.goal(task.sessionId()).orElseThrow().revision()!=task.goalRevision())throw new IllegalStateException("排队任务目标版本已改变");
            TaskDependencies.requireReady(store,task,now());
            if(entry.attempts()>=3){
                store.saveTask(task,task.next(TaskStatus.FAILED,"队列领取次数已达上限，请重新规划",List.of(),false,now()),"queue_claim_limit");
                change(entry,State.DEAD,entry.attempts(),entry.claimId(),entry.lease(),"CLAIM_LIMIT","queue_dead");return Optional.empty();
            }
            String claimId=UUID.randomUUID().toString();change(entry,State.CLAIMED,entry.attempts()+1,claimId,token,"CLAIMED","queue_claimed");
            return Optional.of(new Claim(id,claimId,token));
        }));
    }
    private void requireClaim(Entry entry,Claim claim){
        if(!entry.taskId().equals(claim.taskId())||!entry.claimId().equals(claim.id())||!Objects.equals(entry.lease(),claim.lease()))throw new IllegalStateException("队列领取已失效");
    }
    @Override public void start(Claim claim,TaskRecord expected,TaskRecord running){
        leases.fenced(claim.lease(),()->{tx.executeWithoutResult(status->{
            var current=lockTask(expected.id());var entry=lockEntry(expected.id());requireClaim(entry,claim);leases.require(claim.lease());
            if(entry.state()!=State.CLAIMED||!current.equals(expected)||current.status()!=TaskStatus.QUEUED||!running.equals(current.start(claim.lease(),running.updatedAt()))
                    ||!Objects.equals(running.executionLease(),claim.lease()))throw new IllegalStateException("队列任务不可启动");
            if(store.goal(current.sessionId()).orElseThrow().revision()!=current.goalRevision())throw new IllegalStateException("排队任务目标版本已改变");
            TaskDependencies.requireReady(store,current,now());
            store.saveTask(current,running,"task_running");change(entry,State.STARTED,entry.attempts(),entry.claimId(),entry.lease(),"STARTED_NO_REPLAY","queue_started");
        });return null;});
    }
    @Override public Entry settle(Claim claim){return tx.execute(status->{
        var task=lockTask(claim.taskId());var entry=lockEntry(claim.taskId());requireClaim(entry,claim);
        if(entry.state()==State.CLAIMED&&task.status()==TaskStatus.QUEUED)return change(entry,State.READY,entry.attempts(),"",null,"PRESTART_ABORTED","queue_released");
        return syncLocked(task,entry);
    });}
    @Override public Entry sync(String id){return tx.execute(status->{var task=lockTask(id);return syncLocked(task,lockEntry(id));});}
    private Entry syncLocked(TaskRecord task,Entry entry){
        if(entry.state().terminal())return entry;
        if(task.status()==TaskStatus.QUEUED){
            if(entry.state()==State.CLAIMED&&!leases.valid(entry.lease()))return change(entry,State.READY,entry.attempts(),"",null,"CLAIM_EXPIRED_BEFORE_START","queue_claim_recovered");
            if(entry.state()==State.STARTED)throw new IllegalStateException("队列与任务启动记录不一致");
            return entry;
        }
        State state=switch(task.status()){
            case COMPLETED->State.DONE;
            case NEEDS_INPUT,INPUT_READY,AWAITING_APPROVAL->State.HANDED_OFF;
            case CANCELLED->State.CANCELLED;
            case STALE->State.STALE;
            case FAILED,UNKNOWN->State.DEAD;
            case RUNNING->leases.valid(task.executionLease())?State.STARTED:State.DEAD;
            default->throw new IllegalStateException("任务不属于准备队列");
        };
        if(state==State.STARTED){
            if(entry.state()!=State.STARTED||!Objects.equals(entry.lease(),task.executionLease()))throw new IllegalStateException("队列启动身份不一致");return entry;
        }
        return change(entry,state,entry.attempts(),entry.claimId(),entry.lease(),"TASK_"+task.status(),"queue_settled");
    }
}
