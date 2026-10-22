package org.celanwang.mio.orchestration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;
import java.util.function.Supplier;

/** PostgreSQL time and monotonically increasing generations fence expired or superseded owners. */
public final class JdbcExecutionLeaseStore implements ExecutionLeaseStore {
    private static final String NOW="floor(extract(epoch from clock_timestamp())*1000)::bigint";
    private final JdbcTemplate jdbc;private final TransactionTemplate transaction;
    public JdbcExecutionLeaseStore(JdbcTemplate jdbc,DataSource source){this.jdbc=jdbc;transaction=new TransactionTemplate(new DataSourceTransactionManager(source));}
    public void initialize(){jdbc.execute("CREATE TABLE IF NOT EXISTS execution_leases(resource TEXT PRIMARY KEY,owner TEXT,generation BIGINT NOT NULL,expires_at BIGINT NOT NULL,session_id TEXT NOT NULL)");}
    @Override public Token acquire(String session,long duration){
        if(session==null||session.isBlank()||session.length()>200||duration<1||duration>900000)throw new IllegalArgumentException("会话租约参数无效，单轮最长十五分钟");
        String resource=ExecutionLeaseStore.resource(session),owner=UUID.randomUUID().toString();
        return transaction.execute(tx->{
            var rows=jdbc.query("INSERT INTO execution_leases(resource,owner,generation,expires_at,session_id) VALUES (?,?,1,"+NOW+"+?,?) ON CONFLICT(resource) DO UPDATE SET owner=EXCLUDED.owner,generation=execution_leases.generation+1,expires_at=EXCLUDED.expires_at WHERE execution_leases.owner IS NULL OR execution_leases.expires_at<="+NOW+" RETURNING resource,owner,generation,expires_at",
                    (rs,n)->new Token(rs.getString(1),rs.getString(2),rs.getLong(3),rs.getLong(4)),resource,owner,duration,session);
            if(rows.isEmpty())throw new Busy();var token=rows.get(0);require(token);event(session,"lease_acquired",token);return token;
        });
    }
    @Override public boolean valid(Token token){
        return token!=null&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM execution_leases WHERE resource=? AND owner=? AND generation=? AND expires_at>"+NOW+")",Boolean.class,token.resource(),token.owner(),token.generation()));
    }
    @Override public <T> T fenced(Token token,Supplier<T> action){
        return transaction.execute(tx->{
            var rows=jdbc.queryForList("SELECT resource FROM execution_leases WHERE resource=? FOR UPDATE",String.class,token.resource());
            if(rows.isEmpty())throw new LostLease();require(token);T result=action.get();require(token);return result;
        });
    }
    @Override public void release(Token token){
        transaction.executeWithoutResult(tx->{
            var rows=jdbc.query("UPDATE execution_leases SET owner=NULL,expires_at=0 WHERE resource=? AND owner=? AND generation=? RETURNING session_id",(rs,n)->rs.getString(1),token.resource(),token.owner(),token.generation());
            if(!rows.isEmpty())event(rows.get(0),"lease_released",token);
        });
    }
    private void event(String session,String type,Token token){
        jdbc.update("INSERT INTO events(id,ts,session_id,node,type,title,detail) VALUES (?,"+NOW+",?,?,?,?,?)",
                "lease:"+token.resource()+":"+token.generation()+":"+type,session,"coordination",type,type,
                "generation="+token.generation()+", expiresAt="+token.expiresAt());
    }
}
