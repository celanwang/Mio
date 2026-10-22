package org.celanwang.mio.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.Clock;
import java.util.*;

/** Confirmation and audit are atomic. Lock order follows lease -> goal -> tasks -> acceptance. */
public final class JdbcGoalAcceptance {
    private final JdbcTemplate jdbc;private final CoordinationStore store;private final AgentRegistry registry;private final ObjectMapper json;private final Clock clock;
    private final TransactionTemplate write,read;
    public JdbcGoalAcceptance(JdbcTemplate jdbc,DataSource source,CoordinationStore store,AgentRegistry registry,ObjectMapper json,Clock clock) {
        this.jdbc=jdbc;this.store=store;this.registry=registry;this.json=json;this.clock=clock;
        var manager=new DataSourceTransactionManager(source);write=new TransactionTemplate(manager);read=new TransactionTemplate(manager);
        read.setReadOnly(true);read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public void initialize(){jdbc.execute("CREATE TABLE IF NOT EXISTS goal_acceptances(goal_id TEXT PRIMARY KEY REFERENCES goals(id),revision BIGINT NOT NULL,content TEXT NOT NULL)");}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception failure){throw new IllegalStateException("目标验收序列化失败",failure);}}
    public Optional<GoalAcceptance> find(String goalId) {
        return jdbc.query("SELECT revision,content FROM goal_acceptances WHERE goal_id=?",(rs,n)->{
            try {var value=json.readValue(rs.getString("content"),GoalAcceptance.class);
                if(!value.goalId().equals(goalId)||value.revision()!=rs.getLong("revision"))throw new IllegalStateException("目标验收标识或版本不匹配");return value;
            }catch(Exception failure){throw new IllegalStateException("目标验收记录损坏，不能认定目标达成",failure);}
        },goalId).stream().findFirst();
    }
    public record Snapshot(GoalRecord goal,List<TaskRecord> tasks,GoalAcceptanceView acceptance) {public Snapshot{tasks=List.copyOf(tasks);}}
    public Snapshot snapshot(String session) {
        return read.execute(status->{
            var goal=store.goal(session).orElse(null);var tasks=store.tasks(session);
            return new Snapshot(goal,tasks,goal==null?null:GoalAcceptanceView.evaluate(goal,tasks,find(goal.id()).orElse(null),registry,store,clock.millis()));
        });
    }
    public GoalAcceptance confirm(GoalAcceptance.Request request) {
        if(request==null)throw new IllegalArgumentException("缺少目标验收请求");
        return write.execute(status->{
            String session=request.sessionId(),resource=ExecutionLeaseStore.resource(session);
            jdbc.update("INSERT INTO execution_leases(resource,owner,generation,expires_at,session_id) VALUES (?,NULL,0,0,?) ON CONFLICT DO NOTHING",resource,session);
            jdbc.queryForObject("SELECT resource FROM execution_leases WHERE resource=? FOR UPDATE",String.class,resource);
            var goal=store.goal(session).orElseThrow(()->new IllegalArgumentException("目标不存在"));
            jdbc.queryForObject("SELECT id FROM goals WHERE id=? FOR UPDATE",String.class,goal.id());goal=store.goal(session).orElseThrow();
            if(goal.revision()!=request.expectedGoalRevision()||goal.controlRevision()!=request.expectedControlRevision())throw new IllegalStateException("目标内容或控制状态已改变，请刷新后重新确认验收条件");
            if(goal.state()==GoalRecord.State.CLOSED)throw new IllegalStateException("已关闭目标的验收条件不可修改");
            for(String id:request.selections().stream().map(GoalAcceptance.Selection::taskId).sorted().toList()) {
                var task=store.task(id).orElseThrow(()->new IllegalArgumentException("验收任务不存在"));
                if(!task.sessionId().equals(session))throw new IllegalArgumentException("验收任务不属于当前会话");
                jdbc.queryForObject("SELECT id FROM tasks WHERE id=? FOR UPDATE",String.class,id);
            }
            var previous=find(goal.id()).orElse(null);long expected=previous==null?0:previous.revision();
            if(expected!=request.expectedRevision())throw new IllegalStateException("验收条件已被更新，请刷新后操作");
            var requirements=new ArrayList<GoalAcceptance.Requirement>();
            for(var selected:request.selections()) {
                var task=store.task(selected.taskId()).orElseThrow();
                if(!task.goalId().equals(goal.id())||task.goalRevision()!=goal.revision())throw new IllegalArgumentException("验收任务不属于当前目标版本");
                var capability=registry.require(task.spec().capabilityId());var contract=capability.outputContract(task.spec());
                if(!capability.executionVersion().equals(task.capabilityVersion())||!contract.version().equals(selected.contractVersion()))throw new IllegalStateException("任务能力或输出契约已改变，请重新规划并确认");
                if(contract.requiredFacts().isEmpty())throw new IllegalArgumentException("工作报告不能作为目标达成的证据条件");
                for(var check:selected.checks()) {
                    var field=contract.fields().stream().filter(f->f.id().equals(check.fieldId())).findFirst().orElseThrow(()->new IllegalArgumentException("验收字段未在可信契约中注册"));
                    check.validate(field);
                }
                requirements.add(new GoalAcceptance.Requirement(task.id(),task.capabilityVersion(),contract.id(),contract.version(),selected.checks()));
            }
            var confirmed=new GoalAcceptance(1,goal.id(),goal.revision(),Math.addExact(expected,1),clock.millis(),requirements);
            int changed=previous==null?jdbc.update("INSERT INTO goal_acceptances(goal_id,revision,content) VALUES (?,?,?) ON CONFLICT DO NOTHING",goal.id(),confirmed.revision(),encode(confirmed)):
                    jdbc.update("UPDATE goal_acceptances SET revision=?,content=? WHERE goal_id=? AND revision=?",confirmed.revision(),encode(confirmed),goal.id(),expected);
            if(changed!=1)throw new IllegalStateException("验收条件发生并发修改，请刷新后操作");
            jdbc.update("INSERT INTO events(id,ts,session_id,node,type,title,detail) VALUES (?,?,?,?,?,?,?)","goal-acceptance:"+goal.id()+":"+confirmed.revision(),clock.millis(),session,"goal", "goal_acceptance_confirmed","用户确认验收条件",
                    encode(Map.of("criteria",confirmed,"controlRevision",goal.controlRevision(),"source","USER")));
            return confirmed;
        });
    }
}
