package org.celanwang.mio.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;

/** Lock, counter update and audit record commit together. No volatile production fallback. */
public final class JdbcGoalBudgetStore implements GoalBudgetStore {
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final TransactionTemplate tx;
    public JdbcGoalBudgetStore(JdbcTemplate jdbc,DataSource source,ObjectMapper json){this.jdbc=jdbc;this.json=json;tx=new TransactionTemplate(new DataSourceTransactionManager(source));}
    public void initialize(){
        jdbc.execute("CREATE TABLE IF NOT EXISTS goal_budgets(goal_id TEXT PRIMARY KEY REFERENCES goals(id), content TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS budget_calls(id TEXT PRIMARY KEY, goal_id TEXT NOT NULL REFERENCES goal_budgets(goal_id), state TEXT NOT NULL, tokens BIGINT)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_budget_calls_goal ON budget_calls(goal_id)");
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("预算序列化失败",e);}}
    private Snapshot decode(String value,String goal){try{var s=json.readValue(value,Snapshot.class);if(!s.goalId().equals(goal))throw new IllegalArgumentException("目标标识不匹配");return s;}catch(Exception e){throw new IllegalStateException("目标预算损坏，已停止执行",e);}}
    @Override public Snapshot open(String goalId,Limits limits,boolean historyUnknown){
        return tx.execute(status->{
            int created=jdbc.update("INSERT INTO goal_budgets(goal_id,content) VALUES (?,?) ON CONFLICT (goal_id) DO NOTHING",goalId,encode(new Snapshot(goalId,limits,0,0,0,0,0,0,0,historyUnknown)));
            var snapshot=find(goalId).orElseThrow();
            if(created==1)save(snapshot,goalId+":opened","budget_opened");
            return snapshot;
        });
    }
    @Override public Optional<Snapshot> find(String goalId){return jdbc.query("SELECT content FROM goal_budgets WHERE goal_id=?",(rs,n)->decode(rs.getString(1),goalId),goalId).stream().findFirst();}
    private Snapshot lock(String id){return jdbc.queryForObject("SELECT content FROM goal_budgets WHERE goal_id=? FOR UPDATE",(rs,n)->decode(rs.getString(1),id),id);}
    private void save(Snapshot state,String eventId,String type){
        jdbc.update("UPDATE goal_budgets SET content=? WHERE goal_id=?",encode(state),state.goalId());
        jdbc.update("INSERT INTO events(id,ts,session_id,node,type,title,detail) SELECT ?,?,session_id,'budget',?,?,? FROM goals WHERE id=?",
                "budget:"+eventId,System.currentTimeMillis(),type,type,encode(state),state.goalId());
    }
    @Override public String reserve(String goalId,Kind kind){
        Objects.requireNonNull(kind,"预算类型缺失");
        return tx.execute(status->{
            var s=lock(goalId);var l=s.limits();
            if(s.knownTokens()>=l.tokens()||kind==Kind.MODEL&&s.models()>=l.models()||kind==Kind.TOOL&&s.tools()>=l.tools()||kind==Kind.TASK&&s.tasks()>=l.tasks())
                throw new RunBudget.BudgetExceeded("本目标累计"+(switch(kind){case MODEL->"模型调用";case TOOL->"工具调用";case TASK->"任务恢复";})+"或已报告 token 预算已耗尽，已停止新的执行");
            String call=UUID.randomUUID().toString();
            var next=new Snapshot(goalId,l,s.models()+(kind==Kind.MODEL?1:0),s.tools()+(kind==Kind.TOOL?1:0),s.tasks()+(kind==Kind.TASK?1:0),s.knownTokens(),s.pendingUsage()+(kind==Kind.MODEL?1:0),s.unknownUsage(),s.revision()+1,s.historyUnknown());
            if(kind==Kind.MODEL)jdbc.update("INSERT INTO budget_calls(id,goal_id,state,tokens) VALUES (?,?,'PENDING',NULL)",call,goalId);
            save(next,call,"budget_reserved");return call;
        });
    }
    @Override public void settle(String goalId,String callId,Long knownTokens,boolean complete){
        if(knownTokens!=null&&(knownTokens<0||knownTokens>2L*Integer.MAX_VALUE)||complete&&knownTokens==null)throw new IllegalArgumentException("模型用量无效");
        tx.executeWithoutResult(status->{
            var s=lock(goalId);
            var rows=jdbc.queryForList("SELECT state,tokens FROM budget_calls WHERE id=? AND goal_id=? FOR UPDATE",callId,goalId);
            if(rows.size()!=1)throw new IllegalArgumentException("模型预占不属于本目标");
            var row=rows.get(0);String state=complete?"KNOWN":"UNKNOWN";
            if(!"PENDING".equals(row.get("state"))){
                if(state.equals(row.get("state"))&&Objects.equals(knownTokens,row.get("tokens")))return;
                throw new IllegalStateException("已结算的模型用量不能覆盖");
            }
            long total=Math.addExact(s.knownTokens(),knownTokens==null?0:knownTokens);
            jdbc.update("UPDATE budget_calls SET state=?,tokens=? WHERE id=?",state,knownTokens,callId);
            save(new Snapshot(goalId,s.limits(),s.models(),s.tools(),s.tasks(),total,s.pendingUsage()-1,s.unknownUsage()+(complete?0:1),s.revision()+1,s.historyUnknown()),callId+":settled","budget_settled");
        });
    }
}
