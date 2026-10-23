package org.celanwang.mio.goals;

import com.fasterxml.jackson.databind.*;
import org.celanwang.mio.orchestration.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;

/** A resource's lifecycle is separate from operation creation and from historical task completion. */
public final class JdbcBusinessResources {
    public enum State { CREATED,ACCEPTED,IN_PROGRESS,COMPLETED,CANCELLED,UNKNOWN }
    public record Report(State state,String evidence,String expectedObservation){}
    private final JdbcTemplate jdbc;private final CoordinationStore store;private final TransactionTemplate tx;private final ObjectMapper json;
    public JdbcBusinessResources(JdbcTemplate jdbc,DataSource source,CoordinationStore store,ObjectMapper json){this.jdbc=jdbc;this.store=store;this.json=json;tx=new TransactionTemplate(new DataSourceTransactionManager(source));}
    public void initialize(){
        jdbc.execute("CREATE TABLE IF NOT EXISTS goal_resources(operation_id TEXT PRIMARY KEY,goal_id TEXT NOT NULL REFERENCES goals(id),task_id TEXT NOT NULL REFERENCES tasks(id),provider TEXT NOT NULL,business_id TEXT NOT NULL,created_at BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS resource_observations(id TEXT PRIMARY KEY,operation_id TEXT NOT NULL REFERENCES goal_resources(operation_id),state TEXT NOT NULL,source TEXT NOT NULL,evidence TEXT NOT NULL,observed_at BIGINT NOT NULL)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_resources_goal ON goal_resources(goal_id)");
    }
    public void changed(TaskRecord task){
        for(var op:task.operations()){
            if(op.outcome()==OperationOutcome.CONFIRMED_APPLIED&&!op.businessId().isBlank()){
                jdbc.update("INSERT INTO goal_resources VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING",op.id(),task.goalId(),task.id(),task.spec().capabilityId(),op.businessId(),op.startedAt());
                for(var receipt:op.receipts())if(receipt.outcome()==OperationOutcome.CONFIRMED_APPLIED&&receipt.businessId().equals(op.businessId())){
                    observe(op.id(),receipt.id()+":created",State.CREATED,"PROVIDER_RECEIPT","资源已创建；不证明履约完成",receipt.observedAt());
                    if(receipt.source().equals("tencent-correlated-reconciliation-v1"))providerStatus(op,receipt);
                }
            } else if(op.outcome()==OperationOutcome.UNKNOWN&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM goal_resources WHERE operation_id=?)",Boolean.class,op.id())))
                observe(op.id(),"conflict:"+task.revision(),State.UNKNOWN,"RECEIPT_CONFLICT","关联业务结果尚未核实",task.updatedAt());
        }
    }
    private void providerStatus(OperationRecord op,OperationReceipt receipt){
        try{
            var root=json.readTree(receipt.output());var current=root.path("currentOrder");if(current.isTextual())current=json.readTree(current.asText());
            if(current.path("status").asInt()!=200||!current.path("body").path("code").asText().equals("0"))return;
            var data=current.path("body").path("data");if(data.path("detail").isObject())data=data.path("detail");
            if(!data.path("orderId").isTextual()||!data.path("orderId").asText().equals(op.businessId()))return;
            String detail="供应商状态="+data.path("status").asText()+"；"+data.path("statusDesc").asText();
            // Numeric supplier state meanings are not guessed. Preserve its observation for operator review.
            observe(op.id(),receipt.id()+":observed",State.UNKNOWN,"PROVIDER_STATUS",detail.substring(0,Math.min(1000,detail.length())),receipt.observedAt());
        }catch(org.springframework.dao.DataAccessException storage){throw storage;}catch(Exception malformed){/* Creation evidence remains usable; malformed status cannot become fulfillment. */}
    }
    private void observe(String operation,String id,State state,String source,String evidence,long at){jdbc.update("INSERT INTO resource_observations VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING",id,operation,state.name(),source,evidence,at);}
    public List<Map<String,Object>> list(String session){
        var goal=store.goal(session).orElseThrow();
        return jdbc.queryForList("SELECT r.*,o.id AS observation_id,o.state,o.source,o.evidence,o.observed_at FROM goal_resources r LEFT JOIN LATERAL (SELECT * FROM resource_observations WHERE operation_id=r.operation_id ORDER BY observed_at DESC,id DESC LIMIT 1) o ON true WHERE r.goal_id=? ORDER BY r.created_at DESC",goal.id());
    }
    public void report(String session,String operation,Report report){
        if(report==null||report.state()==null||report.evidence()==null||report.evidence().isBlank()||report.evidence().length()>2000)throw new IllegalArgumentException("请填写核实依据");
        tx.executeWithoutResult(status->{var goal=store.goal(session).orElseThrow();jdbc.queryForObject("SELECT operation_id FROM goal_resources WHERE operation_id=? AND goal_id=? FOR UPDATE",String.class,operation,goal.id());
            var latest=jdbc.query("SELECT id FROM resource_observations WHERE operation_id=? ORDER BY observed_at DESC,id DESC LIMIT 1",(r,n)->r.getString(1),operation).stream().findFirst().orElse("");if(!Objects.equals(latest,report.expectedObservation()))throw new IllegalStateException("资源状态已更新，请刷新");
            String id="user:"+UUID.randomUUID();long now=System.currentTimeMillis();observe(operation,id,report.state(),"USER_REPORTED",report.evidence(),now);
            jdbc.update("INSERT INTO events VALUES (?,?,?,?,?,?,?)","resource:"+id,now,session,"resource","resource_user_reported","resource_user_reported",operation+":"+report.state());
        });
    }
    public List<Map<String,Object>> history(String session,String operation){var goal=store.goal(session).orElseThrow();return jdbc.queryForList("SELECT o.* FROM resource_observations o JOIN goal_resources r ON r.operation_id=o.operation_id WHERE r.goal_id=? AND r.operation_id=? ORDER BY o.observed_at,o.id",goal.id(),operation);}
}
