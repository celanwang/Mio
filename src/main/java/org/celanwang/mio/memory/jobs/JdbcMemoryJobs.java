package org.celanwang.mio.memory.jobs;

import org.celanwang.mio.memory.EpisodeStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;

/** Message + jobs commit together. Claims are bounded and obsolete owners cannot publish results. */
public final class JdbcMemoryJobs {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final int maxAttempts;private final long leaseMillis,retryMillis;
    public JdbcMemoryJobs(JdbcTemplate jdbc,DataSource source,int maxAttempts,long leaseMillis,long retryMillis){
        if(maxAttempts<1||maxAttempts>10||leaseMillis<90000||leaseMillis>900000||retryMillis<1||retryMillis>3600000)throw new IllegalArgumentException("记忆任务重试或租约配置无效");
        this.jdbc=jdbc;this.tx=new TransactionTemplate(new DataSourceTransactionManager(source));this.maxAttempts=maxAttempts;this.leaseMillis=leaseMillis;this.retryMillis=retryMillis;
    }
    private String indexNamespace="legacy";
    public void configureIndex(String namespace){this.indexNamespace=namespace==null?"legacy":namespace;}
    private String cursorId(){return indexNamespace.equals("legacy")?"episode-index-v1":"episode-index-v2:"+indexNamespace;}
    public void initialize(){
        jdbc.execute("CREATE TABLE IF NOT EXISTS forgotten_episodes(id TEXT PRIMARY KEY,forgotten_at BIGINT NOT NULL)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS memory_jobs(
                  id TEXT PRIMARY KEY,message_id TEXT NOT NULL REFERENCES messages(id),kind TEXT NOT NULL,
                  sequence BIGSERIAL UNIQUE,priority INTEGER NOT NULL DEFAULT 1,state TEXT NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,
                  generation BIGINT NOT NULL DEFAULT 0,owner TEXT,lease_until BIGINT NOT NULL DEFAULT 0,
                  next_attempt_at BIGINT NOT NULL DEFAULT 0,result TEXT,error_code TEXT NOT NULL DEFAULT '',
                  created_at BIGINT NOT NULL,updated_at BIGINT NOT NULL)
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_memory_jobs_ready ON memory_jobs(state,next_attempt_at,sequence)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS memory_backfills(id TEXT PRIMARY KEY,cursor TEXT NOT NULL DEFAULT '',finished INTEGER NOT NULL DEFAULT 0)");
        jdbc.execute("INSERT INTO memory_backfills(id) VALUES ('episode-index-v1') ON CONFLICT DO NOTHING");
        jdbc.execute("CREATE TABLE IF NOT EXISTS memory_job_lanes(id TEXT PRIMARY KEY)");jdbc.execute("INSERT INTO memory_job_lanes(id) VALUES ('DISTILL') ON CONFLICT DO NOTHING");
    }
    public long now(){return jdbc.queryForObject("SELECT floor(extract(epoch FROM clock_timestamp())*1000)::bigint",Long.class);}
    private static final RowMapper<MemoryJob> ROW=(r,n)->new MemoryJob(r.getString("id"),r.getString("message_id"),MemoryJob.Kind.valueOf(r.getString("kind")),r.getLong("sequence"),MemoryJob.State.valueOf(r.getString("state")),r.getInt("attempts"),r.getLong("generation"),r.getString("owner"),r.getLong("lease_until"),r.getLong("next_attempt_at"),r.getString("result"),r.getString("error_code"),r.getLong("created_at"),r.getLong("updated_at"));
    public Optional<MemoryJob> find(String id){return jdbc.query("SELECT * FROM memory_jobs WHERE id=?",ROW,id).stream().findFirst();}
    public EpisodeStore.AppendResult append(EpisodeStore.Episode episode,boolean index,boolean distill){
        return tx.execute(status->{
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext('mio-episode'),hashtext(?))",Object.class,episode.id());
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM forgotten_episodes WHERE id=?)",Boolean.class,episode.id())))return EpisodeStore.AppendResult.EXISTING;
            int inserted=jdbc.update("INSERT INTO messages(id,session_id,ts,role,text) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",episode.id(),episode.sessionId(),episode.timestamp(),episode.role(),episode.text());
            if(inserted==0){
                Integer same=jdbc.queryForObject("SELECT COUNT(*) FROM messages WHERE id=? AND session_id IS NOT DISTINCT FROM ? AND role=? AND text=?",Integer.class,episode.id(),episode.sessionId(),episode.role(),episode.text());
                if(!Integer.valueOf(1).equals(same))throw new IllegalArgumentException("消息身份与原文冲突");
                return EpisodeStore.AppendResult.EXISTING;
            }
            if(index)enqueue(episode.id(),MemoryJob.Kind.INDEX);
            if(distill&&episode.role().equals("user"))enqueue(episode.id(),MemoryJob.Kind.DISTILL);
            return EpisodeStore.AppendResult.INSERTED;
        });
    }
    private void enqueue(String messageId,MemoryJob.Kind kind){enqueue(messageId,kind,kind==MemoryJob.Kind.DISTILL?0:1);}
    private void enqueue(String messageId,MemoryJob.Kind kind,int priority){long at=now();jdbc.update("INSERT INTO memory_jobs(id,message_id,kind,priority,state,created_at,updated_at) VALUES (?,?,?,?,'READY',?,?) ON CONFLICT DO NOTHING",(kind==MemoryJob.Kind.INDEX&&!indexNamespace.equals("legacy")?"INDEX:"+indexNamespace+":"+messageId:kind.name()+":"+messageId),messageId,kind.name(),priority,at,at);}
    /** Only legacy index work is backfilled. Historical user text is never silently re-distilled. */
    public int backfillPage(int limit){
        if(limit<1||limit>500)throw new IllegalArgumentException("分页大小无效");
        return tx.execute(status->{
            jdbc.update("INSERT INTO memory_backfills(id) VALUES (?) ON CONFLICT DO NOTHING",cursorId());
            var cursor=jdbc.queryForMap("SELECT cursor,finished FROM memory_backfills WHERE id=? FOR UPDATE",cursorId());
            if(((Number)cursor.get("finished")).intValue()==1)return 0;
            if(jdbc.queryForObject("SELECT COUNT(*) FROM memory_jobs WHERE state IN ('READY','RUNNING','RETRY') AND kind='INDEX' AND id LIKE ?",Long.class,indexNamespace.equals("legacy")?"INDEX:%":"INDEX:"+indexNamespace+":%")>=1000)return 0;
            var ids=jdbc.query("SELECT m.id FROM messages m WHERE m.id>? ORDER BY m.id LIMIT ?",(r,n)->r.getString(1),cursor.get("cursor"),limit);
            for(String id:ids)if(!Boolean.TRUE.equals(indexNamespace.equals("legacy")?jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM embeddings WHERE id=?)",Boolean.class,id):jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM episode_vectors v JOIN messages m ON m.id=v.id WHERE v.namespace=? AND v.id=? AND v.source_hash=md5(m.text))",Boolean.class,indexNamespace,id)))enqueue(id,MemoryJob.Kind.INDEX,5);
            if(ids.isEmpty())jdbc.update("UPDATE memory_backfills SET finished=1 WHERE id=?",cursorId());
            else jdbc.update("UPDATE memory_backfills SET cursor=? WHERE id=?",ids.get(ids.size()-1),cursorId());
            return ids.size();
        });
    }
    public Optional<MemoryJob.Claim> claim(Set<MemoryJob.Kind> enabled){
        if(enabled.isEmpty())return Optional.empty();
        return tx.execute(status->{
            long at=now();
            jdbc.update("UPDATE memory_jobs SET state='DEAD',owner=NULL,generation=generation+1,error_code='ATTEMPTS_EXHAUSTED',updated_at=? WHERE state='RUNNING' AND lease_until<=? AND attempts>=CASE WHEN result IS NULL THEN ? ELSE ? END",at,at,maxAttempts,maxAttempts+2);
            var kinds=new ArrayList<>(enabled.stream().map(Enum::name).sorted().toList());String firstDistill="";
            if(kinds.contains("DISTILL")){
                var lane=jdbc.query("SELECT id FROM memory_job_lanes WHERE id='DISTILL' FOR UPDATE SKIP LOCKED",(r,n)->r.getString(1));
                var first=lane.isEmpty()?List.<MemoryJob>of():jdbc.query("SELECT * FROM memory_jobs WHERE kind='DISTILL' AND state IN ('READY','RETRY','RUNNING') ORDER BY sequence LIMIT 1",ROW);
                if(first.isEmpty()||first.get(0).nextAttemptAt()>at||first.get(0).state()==MemoryJob.State.RUNNING&&first.get(0).leaseUntil()>at)kinds.remove("DISTILL");
                else firstDistill=first.get(0).id();
            }
            if(kinds.isEmpty())return Optional.empty();String placeholders=String.join(",",Collections.nCopies(kinds.size(),"?"));
            var args=new ArrayList<Object>(kinds);args.add(at);args.add(at);args.add(maxAttempts);args.add(maxAttempts+2);args.add(firstDistill);args.add(indexNamespace.equals("legacy")?"INDEX:%":"INDEX:"+indexNamespace+":%");
            var rows=jdbc.query("SELECT * FROM memory_jobs WHERE kind IN ("+placeholders+") AND ((state IN ('READY','RETRY') AND next_attempt_at<=?) OR (state='RUNNING' AND lease_until<=?)) AND attempts<CASE WHEN result IS NULL THEN ? ELSE ? END AND (kind<>'DISTILL' OR id=?) AND (kind<>'INDEX' OR id LIKE ?) ORDER BY CASE WHEN result IS NOT NULL THEN 0 ELSE 1 END,priority,sequence FOR UPDATE SKIP LOCKED LIMIT 1",ROW,args.toArray());
            if(rows.isEmpty())return Optional.empty();var old=rows.get(0);String owner=UUID.randomUUID().toString();
            jdbc.update("UPDATE memory_jobs SET state='RUNNING',attempts=attempts+1,generation=generation+1,owner=?,lease_until=?,error_code='',updated_at=? WHERE id=?",owner,Math.addExact(at,leaseMillis),at,old.id());
            return Optional.of(find(old.id()).orElseThrow().claim());
        });
    }
    private MemoryJob locked(MemoryJob.Claim claim){
        var rows=jdbc.query("SELECT * FROM memory_jobs WHERE id=? FOR UPDATE",ROW,claim.id());if(rows.size()!=1)throw new MemoryJob.LostClaim();
        var current=rows.get(0);
        if(current.state()!=MemoryJob.State.RUNNING||current.generation()!=claim.generation()||!Objects.equals(current.owner(),claim.owner())||current.leaseUntil()<=now())throw new MemoryJob.LostClaim();
        return current;
    }
    public void checkpoint(MemoryJob.Claim claim,String result){
        if(result==null||result.length()>300000)throw new IllegalArgumentException("记忆任务结果过长或为空");
        tx.executeWithoutResult(status->{var current=locked(claim);if(current.result()!=null&&!current.result().equals(result))throw new IllegalStateException("已保存结果不可覆盖");jdbc.update("UPDATE memory_jobs SET result=?,updated_at=? WHERE id=?",result,now(),claim.id());});
    }
    public void complete(MemoryJob.Claim claim,Runnable apply){
        completeWithOutcome(claim,()->{apply.run();return "";});
    }
    public void completeWithOutcome(MemoryJob.Claim claim,java.util.function.Supplier<String> apply){
        tx.executeWithoutResult(status->{locked(claim);String outcome=apply.get();locked(claim);jdbc.update("UPDATE memory_jobs SET state='DONE',owner=NULL,lease_until=0,error_code=?,updated_at=? WHERE id=?",outcome,now(),claim.id());});
    }
    public void failed(MemoryJob.Claim claim,String code){
        if(code==null||!code.matches("[A-Z_]{1,60}"))throw new IllegalArgumentException("错误代码无效");
        tx.executeWithoutResult(status->{var current=locked(claim);long at=now();jdbc.update("UPDATE memory_jobs SET state=?,owner=NULL,lease_until=0,next_attempt_at=?,error_code=?,updated_at=? WHERE id=?",current.attempts()>=(current.result()==null?maxAttempts:maxAttempts+2)?"DEAD":"RETRY",at+Math.min(3600000L,retryMillis*(1L<<Math.min(10,current.attempts()-1))),code,at,claim.id());});
    }
    public void defer(MemoryJob.Claim claim,long until,String code){
        if(until<=now()||code==null||!code.matches("[A-Z_]{1,60}"))throw new IllegalArgumentException("延后参数无效");
        tx.executeWithoutResult(status->{locked(claim);jdbc.update("UPDATE memory_jobs SET state='RETRY',attempts=attempts-1,owner=NULL,lease_until=0,next_attempt_at=?,error_code=?,updated_at=? WHERE id=?",until,code,now(),claim.id());});
    }
    public Map<String,Object> diagnostics(){
        return Map.of("counts",jdbc.queryForList("SELECT kind,state,COUNT(*) AS count,MIN(created_at) AS oldest_created_at FROM memory_jobs GROUP BY kind,state ORDER BY kind,state"),
                "recent",jdbc.query("SELECT * FROM memory_jobs ORDER BY updated_at DESC,sequence DESC LIMIT 30",ROW).stream().map(MemoryJob::view).toList(),
                "backfill",jdbc.queryForList("SELECT id,finished FROM memory_backfills"));
    }
}
