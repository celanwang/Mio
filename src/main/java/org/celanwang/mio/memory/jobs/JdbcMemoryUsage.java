package org.celanwang.mio.memory.jobs;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;

/** UTC-day background reservations. Limits cap calls and input; tokens are reported usage, not a billing guarantee. */
public final class JdbcMemoryUsage implements MemoryCallBudget {
    public record Limits(int calls,long inputChars,long knownTokens){public Limits{if(calls<1||inputChars<1||knownTokens<1)throw new IllegalArgumentException("记忆用量额度必须为正数");}}
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final Limits limits;
    public JdbcMemoryUsage(JdbcTemplate jdbc,DataSource source,Limits limits){this.jdbc=jdbc;this.tx=new TransactionTemplate(new DataSourceTransactionManager(source));this.limits=limits;}
    public void initialize(){
        jdbc.execute("CREATE TABLE IF NOT EXISTS memory_usage_days(day TEXT PRIMARY KEY,calls INTEGER NOT NULL DEFAULT 0,input_chars BIGINT NOT NULL DEFAULT 0,known_tokens BIGINT NOT NULL DEFAULT 0,pending INTEGER NOT NULL DEFAULT 0,unknown INTEGER NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS memory_usage_calls(id TEXT PRIMARY KEY,day TEXT NOT NULL REFERENCES memory_usage_days(day),kind TEXT NOT NULL,job_id TEXT,generation BIGINT,state TEXT NOT NULL,tokens BIGINT,created_at BIGINT NOT NULL,expires_at BIGINT NOT NULL)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_memory_usage_pending ON memory_usage_calls(state,expires_at)");
    }
    private String day(){return jdbc.queryForObject("SELECT to_char(clock_timestamp() AT TIME ZONE 'UTC','YYYY-MM-DD')",String.class);}
    public long nextReset(){return jdbc.queryForObject("SELECT (extract(epoch FROM (date_trunc('day',clock_timestamp() AT TIME ZONE 'UTC') + interval '1 day') AT TIME ZONE 'UTC')*1000)::bigint",Long.class);}
    @Override public Call begin(Kind kind,int inputChars,MemoryJob.Claim claim){
        Objects.requireNonNull(kind);if(inputChars<0)throw new IllegalArgumentException("后台调用输入长度无效");
        if(inputChars>limits.inputChars())throw new InputTooLarge();
        String call=tx.execute(status->{
            String day=day();jdbc.update("INSERT INTO memory_usage_days(day) VALUES (?) ON CONFLICT DO NOTHING",day);
            var row=jdbc.queryForMap("SELECT * FROM memory_usage_days WHERE day=? FOR UPDATE",day);
            if(((Number)row.get("calls")).longValue()>=limits.calls()||((Number)row.get("input_chars")).longValue()>limits.inputChars()-inputChars||((Number)row.get("known_tokens")).longValue()>=limits.knownTokens())throw new Exhausted();
            if(claim!=null){
                Integer active=jdbc.queryForObject("SELECT COUNT(*) FROM memory_jobs WHERE id=? AND state='RUNNING' AND generation=? AND owner=? AND lease_until>floor(extract(epoch FROM clock_timestamp())*1000)",Integer.class,claim.id(),claim.generation(),claim.owner());
                if(!Integer.valueOf(1).equals(active))throw new MemoryJob.LostClaim();
            }
            String id=UUID.randomUUID().toString();
            jdbc.update("UPDATE memory_usage_days SET calls=calls+1,input_chars=input_chars+?,pending=pending+1 WHERE day=?",inputChars,day);
            jdbc.update("INSERT INTO memory_usage_calls(id,day,kind,job_id,generation,state,created_at,expires_at) VALUES (?,?,?,?,?,'PENDING',floor(extract(epoch FROM clock_timestamp())*1000),floor(extract(epoch FROM clock_timestamp())*1000)+90000)",id,day,kind.name(),claim==null?null:claim.id(),claim==null?null:claim.generation());
            return id;
        });
        return (tokens,complete)->settle(call,tokens,complete);
    }
    private void settle(String id,Long tokens,boolean complete){
        if(tokens!=null&&(tokens<0||tokens>2L*Integer.MAX_VALUE)||complete&&tokens==null)throw new IllegalArgumentException("后台 token 用量无效");
        tx.executeWithoutResult(status->{
            String day=jdbc.queryForObject("SELECT day FROM memory_usage_calls WHERE id=?",String.class,id);
            jdbc.queryForObject("SELECT day FROM memory_usage_days WHERE day=? FOR UPDATE",String.class,day);
            var row=jdbc.queryForMap("SELECT state,tokens FROM memory_usage_calls WHERE id=? FOR UPDATE",id);
            String previous=(String)row.get("state");Long prior=row.get("tokens")==null?null:((Number)row.get("tokens")).longValue();
            if(previous.equals("KNOWN")){if(!complete||!Objects.equals(tokens,prior))throw new IllegalStateException("已知后台用量不能覆盖");return;}
            if(previous.equals("UNKNOWN")&&!complete)return;
            if(prior!=null&&tokens!=null&&tokens<prior)throw new IllegalStateException("后台已报告用量不能减少");
            long delta=(tokens==null?0:tokens)-(prior==null?0:prior);
            int pendingDelta=previous.equals("PENDING")?-1:0,unknownDelta=complete?(previous.equals("UNKNOWN")?-1:0):(previous.equals("PENDING")?1:0);
            jdbc.update("UPDATE memory_usage_days SET known_tokens=known_tokens+?,pending=pending+?,unknown=unknown+? WHERE day=?",delta,pendingDelta,unknownDelta,day);
            jdbc.update("UPDATE memory_usage_calls SET state=?,tokens=? WHERE id=?",complete?"KNOWN":"UNKNOWN",tokens,id);
        });
    }
    public void recoverExpired(){
        for(String day:jdbc.query("SELECT DISTINCT day FROM memory_usage_calls WHERE state='PENDING' AND expires_at<=floor(extract(epoch FROM clock_timestamp())*1000)",(r,n)->r.getString(1))){
            tx.executeWithoutResult(status->{
                jdbc.queryForObject("SELECT day FROM memory_usage_days WHERE day=? FOR UPDATE",String.class,day);
                int changed=jdbc.update("UPDATE memory_usage_calls SET state='UNKNOWN' WHERE day=? AND state='PENDING' AND expires_at<=floor(extract(epoch FROM clock_timestamp())*1000)",day);
                jdbc.update("UPDATE memory_usage_days SET pending=pending-?,unknown=unknown+? WHERE day=?",changed,changed,day);
            });
        }
    }
    public Map<String,Object> diagnostics(){String day=day();return Map.of("day",day,"timezone","UTC","limits",limits,"nextResetAt",nextReset(),"usage",jdbc.queryForList("SELECT day,calls,input_chars,known_tokens,pending,unknown FROM memory_usage_days WHERE day=?",day));}
}
